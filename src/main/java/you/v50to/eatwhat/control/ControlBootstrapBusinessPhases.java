package you.v50to.eatwhat.control;

import java.util.*;
import static you.v50to.eatwhat.control.ControlBootstrapJournal.Phase.BUSINESS_INSTALL;
import static you.v50to.eatwhat.control.ControlBootstrapBusinessOperations.Operation.*;

/** One bounded native operation/observation per invocation. This phase installs
 * only; network successor, enrollment and final independent health checks must
 * still complete before the composite workflow can report success. */
public final class ControlBootstrapBusinessPhases {
    private final ControlServerStore store;private final ControlBootstrapAdmission admission;private final ControlBootstrapNativePhases nativePhases;
    private final ControlBootstrapBusinessOperations operations;private final ControlBootstrapBusinessFactory factory;private final ControlBootstrapJournal journal;
    public ControlBootstrapBusinessPhases(ControlServerStore store,ControlBootstrapAdmission admission,ControlBootstrapNativePhases nativePhases,
            ControlBootstrapBusinessOperations operations,ControlBootstrapBusinessFactory factory,ControlBootstrapJournal journal){
        this.store=store;this.admission=admission;this.nativePhases=nativePhases;this.operations=operations;this.factory=factory;this.journal=journal;
    }
    private static void require(boolean ok,String code){if(!ok)throw new ControlFailure(409,code);}
    private Map<String,Object> result(ControlBootstrapAdmission.Frozen f){var result=new LinkedHashMap<>(journal.job(f.spec().id()));result.put("businessOperations",operations.progress(f));return result;}
    public Map<String,Object> advance(ControlBootstrapAdmission.Frozen f){
        nativePhases.server(f,BUSINESS_INSTALL);factory.ensure(f);var parent=operations.initialize(f);var progress=operations.progress(f);
        for(var operation:ControlBootstrapBusinessOperations.Operation.values()){
            if("FAILED".equals(progress.get(operation.name()))){
                var receipt=operations.terminalReceipt(f,operation);project(f,receipt);
                journal.observe(f.spec().id(),BUSINESS_INSTALL,parent.claimToken(),receipt);return result(f);
            }
            if("SUCCEEDED".equals(progress.get(operation.name())))continue;
            var permit=operations.claim(f,operation);var input=permit.input();var payload=new LinkedHashMap<>(input.header());
            if(operation==PREPARE&&permit.dispatchAllowed())payload.put("agent",input.agent());
            String command=operation==PREPARE?(permit.dispatchAllowed()?"prepare":"prepare-status"):(permit.dispatchAllowed()?"dispatch":"status");
            var receipt=nativePhases.call(nativePhases.server(f,BUSINESS_INSTALL),input.agent(),List.of(command),payload);
            operations.observe(f,permit,receipt);
            if(operation==INSTALL||"FAILED".equals(receipt.get("state"))){
                if(Set.of("SUCCEEDED","FAILED").contains(receipt.get("state")))project(f,operations.terminalReceipt(f,operation));
                journal.observe(f.spec().id(),BUSINESS_INSTALL,parent.claimToken(),receipt);
            }return result(f);
        }
        // Recover a crash after the terminal receipt was saved, without
        // starting either native operation again or resetting a later JOIN.
        var receipt=operations.terminalReceipt(f,INSTALL);project(f,receipt);
        journal.observe(f.spec().id(),BUSINESS_INSTALL,parent.claimToken(),receipt);return result(f);
    }
    @SuppressWarnings("unchecked") private void project(ControlBootstrapAdmission.Frozen f,Map<String,Object> receipt){
        String job=ControlBootstrapJournal.phaseId(f.spec().id(),BUSINESS_INSTALL).toString();
        store.mutate(data->{
            var server=(Map<String,Object>)((Map<String,Object>)data.get("servers")).get(f.spec().targetServerId().toString());
            require(server!=null,"SERVER_REVISION_CHANGED");admission.requireRegisteredServer(f,false,server);
            Object existing=server.get("installation");
            require(existing==null||existing instanceof Map<?,?>,"BOOTSTRAP_BUSINESS_INSTALLATION_CHANGED");
            // The real inventory creates this exact sentinel before any
            // installer job exists. Accept only that untouched representation;
            // a different job or extra retained metadata must stay protected.
            if(existing instanceof Map<?,?> prior&&!prior.isEmpty()
                    &&!(prior.keySet().equals(Set.of("state"))&&"NOT_INSTALLED".equals(prior.get("state")))){
                require(job.equals(prior.get("jobId")),"BOOTSTRAP_BUSINESS_INSTALLATION_CHANGED");
                if(Set.of("SUCCEEDED","FAILED").contains(prior.get("state"))){
                    require(receipt.get("state").equals(prior.get("state")),"BOOTSTRAP_BUSINESS_INSTALLATION_CHANGED");
                    if("SUCCEEDED".equals(prior.get("state"))){
                        for(String key:List.of("siteId","instanceId","mode","clusterId","allocationSlot","httpPort","root","restoreEvidenceId"))
                            require(Objects.equals(receipt.get(key),prior.get(key)),"BOOTSTRAP_BUSINESS_INSTALLATION_CHANGED");
                        require(receipt.get("artifactSha256").equals(prior.get("currentSha256"))&&f.review().get("appBaseUrl").equals(prior.get("appBaseUrl")),"BOOTSTRAP_BUSINESS_INSTALLATION_CHANGED");
                    }return null;
                }
            }
            var installation=new LinkedHashMap<String,Object>();installation.put("jobId",job);installation.put("bootstrapJobId",f.spec().id().toString());installation.put("state",receipt.get("state"));
            installation.put("clusterMembership","NOT_JOINED");installation.put("appBaseUrl",f.review().get("appBaseUrl"));
            if("SUCCEEDED".equals(receipt.get("state"))){
                for(String key:List.of("siteId","instanceId","mode","clusterId","allocationSlot","httpPort","root","restoreEvidenceId"))installation.put(key,receipt.get(key));
                installation.put("currentSha256",receipt.get("artifactSha256"));installation.put("networkRevalidationRequired",true);installation.put("businessHealthRevalidated",false);
                if(Boolean.TRUE.equals(receipt.get("kernelIpv4DeltaVerified"))){
                    installation.put("kernelIpv4DeltaVerified",true);
                    for(String key:List.of("createdNetworkId","createdNetworkProofSha256","networkBaselineSha256"))installation.put(key,receipt.get(key));
                }
                if(Boolean.TRUE.equals(receipt.get("controllerServicesRevalidated")))installation.put("controllerServicesRevalidated",true);
                for(String key:List.of("firewallDeltaVerified","ipv6DeltaVerified"))if(Boolean.TRUE.equals(receipt.get(key)))installation.put(key,true);
            }else installation.put("code","BOOTSTRAP_PHASE_FAILED_PRESERVED");
            server.put("installation",installation);return null;
        });
    }
}
