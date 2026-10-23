package you.v50to.eatwhat.control;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import static you.v50to.eatwhat.control.ControlBootstrapJournal.Phase.*;

/** Creates and seals the composite installer's original private inputs. Only
 * server-owned catalog/configuration enters this factory. A reserved identity
 * is not a JOIN, storage-reachability or network-successor proof. */
public final class ControlBootstrapBusinessFactory {
    static final String PROTOCOL="eatwhat-controller-bootstrap-business-v1";
    static final String NETWORK_PERMISSION="ALLOW_BUSINESS_DOCKER_NETWORKING";
    static final String FORWARD_POLICY_PERMISSION="ALLOW_DOCKER_FORWARD_POLICY_DROP";
    private final ControlBootstrapNativePhases nativePhases;private final ControlBootstrapNativeInputs inputs;
    private final ControlBootstrapJournal journal;private final ControlBootstrapBusinessAllocation allocations;
    private final ControlBootstrapTargetNetwork network;
    public ControlBootstrapBusinessFactory(ControlBootstrapNativePhases nativePhases,ControlBootstrapNativeInputs inputs,
            ControlBootstrapJournal journal,ControlBootstrapBusinessAllocation allocations,ControlBootstrapTargetNetwork network){
        this.nativePhases=nativePhases;this.inputs=inputs;this.journal=journal;this.allocations=allocations;this.network=network;
    }
    private static void require(boolean ok,String code){if(!ok)throw new ControlFailure(409,code);}
    public void ensure(ControlBootstrapAdmission.Frozen f){
        nativePhases.bound(f);var job=journal.job(f.spec().id());String approval=job.get("requestHash").toString();
        var step=(Map<?,?>)((List<?>)job.get("steps")).get(BUSINESS_INSTALL.ordinal());
        inputs.prepare(f.spec().id(),BUSINESS_INSTALL,approval,f.spec().targetServerId(),f.spec().targetRevision(),f.targetRoute(),
                "PENDING".equals(step.get("state")),()->prepare(f,approval));
    }
    private ControlBootstrapNativeInputs.Prepared prepare(ControlBootstrapAdmission.Frozen f,String approval){
        require(journal.approvedPermissions(f.spec().id()).contains(NETWORK_PERMISSION),"BOOTSTRAP_BUSINESS_NETWORK_SCOPE_REQUIRED");
        require(journal.approvedPermissions(f.spec().id()).contains(FORWARD_POLICY_PERMISSION),"BOOTSTRAP_BUSINESS_FIREWALL_POLICY_SCOPE_REQUIRED");
        var controller=nativePhases.saved(f,CONTROLLER_CONFIG,approval);var verified=journal.successfulReceipt(f.spec().id(),CONTROLLER_VERIFY);
        require(CONTROLLER_VERIFY.protocol.equals(verified.get("protocol"))&&controller.header().get("verifyId").equals(verified.get("id"))
                &&controller.header().get("id").equals(verified.get("configurationId"))&&controller.binding().equals(verified.get("binding"))
                &&"VERIFY".equals(verified.get("operation"))&&"VERIFIED".equals(verified.get("state"))
                &&Boolean.TRUE.equals(verified.get("controllerHealthProven")),"BOOTSTRAP_CONTROLLER_VERIFICATION_REQUIRED");
        // Verify the original accepted catalog before reserving an identity.
        ControlBootstrapControllerCatalog.files(f.artifacts(),controller.header());
        require(((Map<?,?>)controller.header().get("bundle")).containsKey("catalog"),"BOOTSTRAP_BUSINESS_CATALOG_REQUIRED");
        var member=allocations.reserve(f);var business=new LinkedHashMap<String,Object>();
        business.put("serverId",f.spec().targetServerId().toString());business.put("jobId",ControlBootstrapJournal.phaseId(f.spec().id(),BUSINESS_INSTALL).toString());
        business.put("siteId",member.siteId());business.put("instanceId",member.instanceId().toString());business.put("mode",member.mode());
        business.put("clusterId",f.spec().clusterId().toString());business.put("allocationSlot",member.allocationSlot());
        business.put("artifactSha256",f.artifacts().business().sha256());business.put("artifactBytes",f.artifacts().business().size());
        business.put("httpPort",f.review().get("httpPort"));business.put("appBaseUrl",f.review().get("appBaseUrl"));business.put("installDocker",true);
        var database=Map.of("POSTGRES_USER","eatwhat_migration","POSTGRES_PASSWORD",random(),"PG_DB","eatwhat", "PG_APP_USER","eatwhat_app","PG_APP_PASSWORD",random(),"REDIS_PASSWORD",random());
        business.put("database",database);business.put("nodeToken",random());
        var source=new TreeMap<>(network.sourceProperties(f));source.putAll(journal.targetStorageProperties(f));
        business.put("properties",ControlBusinessRuntime.properties(business,database,Objects.toString(business.get("appBaseUrl")),source::get));
        var images=f.artifacts().images();business.put("images",Map.of("app",images.get("jre"),"postgres",images.get("postgres"),"redis",images.get("redis")));
        if(f.artifacts().imageArchive()!=null){business.put("imageArchiveBytes",f.artifacts().imageArchive().size());business.put("imageArchiveSha256",f.artifacts().imageArchive().sha256());}
        String first=ControlServers.resource("first-install.py"),release=ControlServers.resource("remote-release.py"),initdb=ControlServers.resource("initdb/20-create-application-db.sh");
        String policy=ControlServers.resource("controller-bootstrap-business-network.py");
        String services=ControlServers.resource("controller-bootstrap-business-services.py").replace("__EATWHAT_BUSINESS_SERVICES_NFS_POLICY_BASE64__",
                Base64.getEncoder().encodeToString(ControlServers.resource("controller-bootstrap-state-policy.py").getBytes(StandardCharsets.UTF_8)));
        String firewall=ControlServers.resource("controller-bootstrap-business-firewall.py");
        String agent=agent(first,release,initdb,policy,services,firewall);var header=new LinkedHashMap<String,Object>();header.put("id",business.get("jobId"));header.put("bootstrapId",f.spec().id().toString());
        header.put("controllerId",controller.header().get("id"));header.put("controllerBinding",controller.binding());header.put("controllerAgentSha256",controller.header().get("agentSha256"));
        header.put("approvalDigest",approval);header.put("business",business);header.put("firstInstallSha256",ControlBootstrapNativeJson.sha(first));
        header.put("releaseAgentSha256",ControlBootstrapNativeJson.sha(release));header.put("initdbSha256",ControlBootstrapNativeJson.sha(initdb));
        header.put("networkPolicySha256",ControlBootstrapNativeJson.sha(policy));header.put("networkEffects",Map.of("allowNewDaemon",true,"allowIpForwardEnable",true));
        header.put("servicesPolicySha256",ControlBootstrapNativeJson.sha(services));
        header.put("firewallPolicySha256",ControlBootstrapNativeJson.sha(firewall));header.put("firewallEffects",Map.of("allowForwardPolicyDrop",true));
        var checked=nativePhases.call(nativePhases.server(f,BUSINESS_INSTALL),agent,List.of("inspect"),header);
        require(BUSINESS_INSTALL.protocol.equals(checked.get("protocol"))&&PROTOCOL.equals(checked.get("bootstrapProtocol"))
                &&header.get("id").equals(checked.get("jobId"))&&business.get("serverId").equals(checked.get("serverId"))
                &&"INSPECT".equals(checked.get("operation"))&&ControlBootstrapNativeJson.sha(ControlBootstrapNativeJson.encode(header)).equals(checked.get("binding"))
                &&Boolean.FALSE.equals(checked.get("effectsStarted"))&&checked.get("proof") instanceof String proof&&proof.matches("[a-f0-9]{64}"),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        for(String key:List.of("networkContextSha256","kernelBaselineSha256","servicesBaselineSha256","firewallBaselineSha256","ipv6BaselineSha256"))
            require(checked.get(key) instanceof String sha&&sha.matches("[a-f0-9]{64}"),"BOOTSTRAP_NATIVE_RECEIPT_MISMATCH");
        header.put("expectedProof",checked.get("proof"));header.put("expectedNetworkContextSha256",checked.get("networkContextSha256"));
        header.put("expectedKernelBaselineSha256",checked.get("kernelBaselineSha256"));header.put("expectedServicesBaselineSha256",checked.get("servicesBaselineSha256"));
        header.put("expectedFirewallBaselineSha256",checked.get("firewallBaselineSha256"));header.put("expectedIpv6BaselineSha256",checked.get("ipv6BaselineSha256"));
        header.put("agentSha256",ControlBootstrapNativeJson.sha(agent));return new ControlBootstrapNativeInputs.Prepared(header,agent);
    }
    static String agent(String first,String release,String initdb,String policy,String services,String firewall){
        String result=ControlServers.resource("controller-bootstrap-business.py");
        for(var entry:Map.of("__EATWHAT_BUSINESS_FIRST_INSTALL_BASE64__",first,"__EATWHAT_BUSINESS_RELEASE_AGENT_BASE64__",release,"__EATWHAT_BUSINESS_INITDB_BASE64__",initdb,"__EATWHAT_BUSINESS_NETWORK_POLICY_BASE64__",policy,"__EATWHAT_BUSINESS_SERVICES_POLICY_BASE64__",services,"__EATWHAT_BUSINESS_FIREWALL_POLICY_BASE64__",firewall).entrySet()){
            String marker=entry.getKey();require(result.indexOf(marker)>=0&&result.indexOf(marker)==result.lastIndexOf(marker),"BOOTSTRAP_NATIVE_AGENT_INVALID");
            result=result.replace(marker,Base64.getEncoder().encodeToString(entry.getValue().getBytes(StandardCharsets.UTF_8)));
        }
        require(result.getBytes(StandardCharsets.UTF_8).length<=524288,"BOOTSTRAP_NATIVE_AGENT_INVALID");return result;
    }
    private static String random(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
}
