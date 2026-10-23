package you.v50to.eatwhat.control;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.cluster.*;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.SyncTransactions;

/** Explicitly opted-in plan admission, native drivers and independent worker. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"app.control.enabled","app.control.controller-bootstrap.enabled"},havingValue="true")
public class ControlControllerBootstrapConfiguration {
    @Bean ControlBootstrapArtifacts controlBootstrapArtifacts(ControlProperties settings,ControlServerStore store){return new ControlBootstrapArtifacts(settings,store);}
    @Bean ControlBootstrapPreflights controlBootstrapPreflights(ControlServerStore store,ControlServers servers,ControlSsh ssh){return new ControlBootstrapPreflights(store,servers,ssh);}
    @Bean ControlBootstrapAdmission controlBootstrapAdmission(ControlProperties settings,SiteProperties site,ClusterProperties cluster,JdbcTemplate jdbc,SyncTransactions tx,
             ClusterControlLease lease,DeploymentExecutor deployment,ControlServerStore store,ControlServers servers,ControlSsh ssh,
             ControlBootstrapArtifacts artifacts,ControlBootstrapPreflights preflights,ControlBootstrapSources sources){
        return new ControlBootstrapAdmission(settings,site,cluster,jdbc,tx,lease,deployment,store,servers,ssh,artifacts,preflights,sources);
    }
    @Bean ControlBootstrapPhaseClaims controlBootstrapPhaseClaims(ControlBootstrapJournal journal,ControlBootstrapAdmission admission){return new ControlBootstrapPhaseClaims(journal,admission);}
    @Bean ControlBootstrapNativeInputs controlBootstrapNativeInputs(ControlServerStore store){return new ControlBootstrapNativeInputs(store);}
    @Bean ControlBootstrapNativePhases controlBootstrapNativePhases(ControlServerStore store,ControlBootstrapJournal journal,ControlBootstrapAdmission admission,
            ControlBootstrapPhaseClaims claims,ControlBootstrapNativeInputs inputs,ControlSsh ssh){return new ControlBootstrapNativePhases(store,journal,admission,claims,inputs,ssh);}
    @Bean ControlBootstrapSourceInspection controlBootstrapSourceInspection(ControlBootstrapProfileState profiles,ControlBootstrapNativePhases nativePhases,ControlBootstrapSources sources){return new ControlBootstrapSourceInspection(profiles,nativePhases,sources);}
    @Bean ControlBootstrapInspection controlBootstrapInspection(ControlBootstrapJournal journal,ControlBootstrapNativePhases nativePhases,ControlBootstrapMembership membership,ControlBootstrapMemberRuntime runtime,ControlBootstrapSourceInspection source){return new ControlBootstrapInspection(journal,nativePhases,membership,runtime,source);}
    @Bean ControlBootstrapDependencyPhases controlBootstrapDependencyPhases(ControlBootstrapNativePhases nativePhases,ControlBootstrapNativeInputs inputs,
            ControlBootstrapJournal journal,ControlBootstrapPhaseClaims claims,ControlBootstrapTargetNetwork network){return new ControlBootstrapDependencyPhases(nativePhases,inputs,journal,claims,network);}
    @Bean ControlBootstrapControllerOperations controlBootstrapControllerOperations(ControlServerStore store,ControlBootstrapJournal journal,
            ControlBootstrapPhaseClaims claims,ControlBootstrapNativeInputs inputs,ControlBootstrapNativePhases nativePhases){return new ControlBootstrapControllerOperations(store,journal,claims,inputs,nativePhases);}
    @Bean ControlBootstrapControllerPhases controlBootstrapControllerPhases(ControlBootstrapNativePhases nativePhases,ControlBootstrapControllerOperations operations,
            ControlBootstrapPhaseClaims claims,ControlBootstrapJournal journal,ControlBootstrapNativeInputs inputs,ControlBootstrapControllerFactory factory){return new ControlBootstrapControllerPhases(nativePhases,operations,claims,journal,inputs,factory);}
    @Bean ControlBootstrapTargetMaterials controlBootstrapTargetMaterials(ControlBootstrapAdmission admission){return new ControlBootstrapTargetMaterials(admission);}
    @Bean ControlBootstrapTargetNetwork controlBootstrapTargetNetwork(ControlBootstrapAdmission admission,ControlBootstrapTargetMaterials materials){return new ControlBootstrapTargetNetwork(admission,materials);}
    @Bean ControlBootstrapControllerFactory controlBootstrapControllerFactory(ControlBootstrapTargetNetwork network,ControlBootstrapDependencyPhases dependencies,
            ControlBootstrapNativePhases nativePhases,ControlBootstrapNativeInputs inputs,ControlBootstrapJournal journal){return new ControlBootstrapControllerFactory(network,dependencies,nativePhases,inputs,journal);}
    @Bean ControlBootstrapBusinessAllocation controlBootstrapBusinessAllocation(ControlBootstrapAdmission admission,ControlBootstrapJournal journal,
            org.springframework.beans.factory.ObjectProvider<ClusterStore> membership){return new ControlBootstrapBusinessAllocation(admission,journal,membership);}
    @Bean ControlBootstrapBusinessFactory controlBootstrapBusinessFactory(ControlBootstrapNativePhases nativePhases,ControlBootstrapNativeInputs inputs,
            ControlBootstrapJournal journal,ControlBootstrapBusinessAllocation allocations,ControlBootstrapTargetNetwork network){return new ControlBootstrapBusinessFactory(nativePhases,inputs,journal,allocations,network);}
    @Bean ControlBootstrapBusinessOperations controlBootstrapBusinessOperations(ControlServerStore store,ControlBootstrapJournal journal,ControlBootstrapPhaseClaims claims,
            ControlBootstrapNativeInputs inputs,ControlBootstrapNativePhases nativePhases){return new ControlBootstrapBusinessOperations(store,journal,claims,inputs,nativePhases);}
    @Bean ControlBootstrapBusinessPhases controlBootstrapBusinessPhases(ControlServerStore store,ControlBootstrapAdmission admission,ControlBootstrapNativePhases nativePhases,
            ControlBootstrapBusinessOperations operations,ControlBootstrapBusinessFactory factory,ControlBootstrapJournal journal){return new ControlBootstrapBusinessPhases(store,admission,nativePhases,operations,factory,journal);}
    @Bean ControlBootstrapMemberRelay controlBootstrapMemberRelay(ControlServerStore store,ControlBootstrapJournal journal,ControlBootstrapNativePhases nativePhases,
            ControlBootstrapPhaseClaims claims,ControlBootstrapNativeInputs inputs){return new ControlBootstrapMemberRelay(store,journal,nativePhases,claims,inputs);}
    @Bean ControlBootstrapMemberAuth controlBootstrapMemberAuth(ControlServerStore store,ControlBootstrapJournal journal,ControlBootstrapNativePhases nativePhases,
            ControlBootstrapPhaseClaims claims,ControlBootstrapNativeInputs inputs,ControlBootstrapMemberRelay relay){return new ControlBootstrapMemberAuth(store,journal,nativePhases,claims,inputs,relay);}
    @Bean ControlBootstrapMemberIngress controlBootstrapMemberIngress(ControlServerStore store,ControlBootstrapJournal journal,ControlBootstrapNativePhases nativePhases,
            ControlBootstrapPhaseClaims claims,ControlBootstrapNativeInputs inputs,ControlBootstrapMemberRelay relay,ControlBootstrapMemberAuth auth){return new ControlBootstrapMemberIngress(store,journal,nativePhases,claims,inputs,relay,auth);}
    @Bean ControlBootstrapMemberRuntime controlBootstrapMemberRuntime(ControlServerStore store,ControlBootstrapJournal journal,ControlBootstrapNativePhases nativePhases,
            ControlBootstrapPhaseClaims claims,ControlBootstrapNativeInputs inputs,ControlBootstrapMemberRelay relay,ControlBootstrapMemberAuth auth,ControlBootstrapMemberIngress ingress){return new ControlBootstrapMemberRuntime(store,journal,nativePhases,claims,inputs,relay,auth,ingress);}
    @Bean ControlBootstrapProfilePlans controlBootstrapProfilePlans(ControlBootstrapAdmission admission,ControlBootstrapJournal journal,ControlBootstrapNativePhases nativePhases,
            ControlBootstrapMemberRuntime runtime,ControlBootstrapMemberIngress ingress){return new ControlBootstrapProfilePlans(admission,journal,nativePhases,runtime,ingress);}
    @Bean ControlBootstrapProfileState controlBootstrapProfileState(ControlServerStore store,ControlBootstrapJournal journal,ControlBootstrapAdmission admission,
            ControlBootstrapProfilePlans plans,ControlBootstrapNativePhases nativePhases){return new ControlBootstrapProfileState(store,journal,admission,plans,nativePhases);}
    @Bean ControlBootstrapProfileReceiver controlBootstrapProfileReceiver(ControlServerStore store,ControlBootstrapProfileState state,ControlBootstrapAdmission admission,
            ClusterControlLease lease,ClusterProperties cluster,org.springframework.beans.factory.ObjectProvider<ClusterProfiles> profiles){return new ControlBootstrapProfileReceiver(store,state,admission,lease,cluster,profiles,java.nio.file.Path.of("/opt/eatwhat-control"));}
    @Bean ControlBootstrapProfilePhases controlBootstrapProfilePhases(ControlBootstrapProfileState state,ControlBootstrapNativePhases nativePhases){return new ControlBootstrapProfilePhases(state,nativePhases);}
    @Bean ControlBootstrapMembership controlBootstrapMembership(ControlServerStore store,ControlBootstrapJournal journal,ControlBootstrapAdmission admission,ControlBootstrapProfileState state,
            ControlBootstrapMemberRuntime runtime,org.springframework.beans.factory.ObjectProvider<ClusterStore> membership,org.springframework.beans.factory.ObjectProvider<ClusterMembershipCoordinator> coordinator,
            ControlBootstrapNativePhases nativePhases,ControlBootstrapProfileReceiver receiver){return new ControlBootstrapMembership(store,journal,admission,state,runtime,membership,coordinator,nativePhases,receiver);}
    @Bean ControlBootstrapVerify controlBootstrapVerify(ControlServerStore store,ControlBootstrapJournal journal,ControlBootstrapAdmission admission,ControlBootstrapProfileState profiles,
            ControlBootstrapMembership membership,ControlBootstrapMemberRuntime runtime,org.springframework.beans.factory.ObjectProvider<ClusterStore> cluster){return new ControlBootstrapVerify(store,journal,admission,profiles,membership,runtime,cluster);}
    @Bean ControlBootstrapPlans controlBootstrapPlans(ControlServers servers,ControlServerStore store,ControlBootstrapAdmission admission,ControlBootstrapSources sources,
            ControlBootstrapPreflights preflights,ControlBootstrapArtifacts artifacts,ControlBootstrapTargetNetwork network,ControlBootstrapJournal journal){return new ControlBootstrapPlans(servers,store,admission,sources,preflights,artifacts,network,journal);}
    @Bean ControlBootstrapWorkflow controlBootstrapWorkflow(ControlBootstrapJournal journal,ControlBootstrapAdmission admission,ControlBootstrapNativePhases nativePhases,
            ControlBootstrapDependencyPhases dependencies,ControlBootstrapControllerPhases controllers,ControlBootstrapBusinessPhases business,ControlBootstrapMemberRelay relay,
            ControlBootstrapMemberAuth auth,ControlBootstrapMemberIngress ingress,ControlBootstrapMemberRuntime runtime,ControlBootstrapProfilePhases profiles,
            ControlBootstrapMembership membership,ControlBootstrapVerify verification){return new ControlBootstrapWorkflow(journal,admission,nativePhases,dependencies,controllers,business,relay,auth,ingress,runtime,profiles,membership,verification);}
    @Bean ControlBootstrapWorker controlBootstrapWorker(ControlServerStore store,ControlBootstrapJournal journal,ClusterControlLease lease,
            ControlBootstrapWorkflow workflow,DeploymentExecutor deployment){return new ControlBootstrapWorker(store,journal,lease,workflow,deployment);}
}
