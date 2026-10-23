package you.v50to.eatwhat.cluster;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.config.*;
import you.v50to.eatwhat.control.ControlProperties;
import you.v50to.eatwhat.sync.SyncTransactions;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(ClusterProperties.class)
@ConditionalOnProperty(name="app.cluster.enabled",havingValue="true")
public class ClusterConfiguration {
    @Bean @ConditionalOnProperty(name="app.cluster.coordinator",havingValue="true") ClusterControl clusterControl(ClusterStore membership,ClusterProfiles profiles,ClusterHttpNodes nodes,you.v50to.eatwhat.control.ControlAudit audit,ControlProperties settings,you.v50to.eatwhat.control.DeploymentExecutor deployment){return new ClusterControl(membership,profiles,nodes,audit,settings,deployment);}
    @Bean @ConditionalOnProperty(name="app.cluster.auth-store.enabled",havingValue="true") @Conditional(LocalAuthorityStoreNeeded.class) ClusterAuthStore clusterAuthStore(ClusterProperties settings,SiteProperties site){return new ClusterAuthStore(settings,site);}
    static final class LocalAuthorityStoreNeeded implements Condition {public boolean matches(ConditionContext context,org.springframework.core.type.AnnotatedTypeMetadata metadata){return !context.getEnvironment().getProperty("app.account.multiwriter.enabled",Boolean.class,false);}}
    @Bean ClusterSessions clusterSessions(JdbcTemplate jdbc,SyncTransactions tx,ClusterProperties settings,ClusterMember local,ClusterAccountChannel channel,ObjectProvider<ClusterHttpNodes> nodes,ObjectProvider<ClusterProfiles> profiles,ObjectProvider<ClusterAuthStore> authStore){return new ClusterSessions(jdbc,tx,settings,local,channel,nodes,profiles,authStore);}
    @Bean ClusterSessionWeb clusterSessionWeb(ClusterSessions sessions){return new ClusterSessionWeb(sessions);}
    @Bean ClusterRuntime clusterRuntime(ClusterReplayService local,JdbcTemplate jdbc,ObjectProvider<ClusterStore> store,ObjectProvider<ClusterProfiles> profiles,ObjectProvider<ClusterMembershipCoordinator> membership,ObjectProvider<ClusterMeshSynchronizer> mesh,ObjectProvider<ClusterHttpNodes> http,ObjectProvider<ClusterGcCoordinator> gc,ObjectProvider<ClusterControlLease> lease,ObjectProvider<ClusterControlHandoff> handoff){return new ClusterRuntime(local,jdbc,store,profiles,membership,mesh,http,gc,lease,handoff);}
    @Bean @ConditionalOnProperty(name="app.cluster.coordinator",havingValue="true") ClusterGcCoordinator clusterGcCoordinator(ClusterGcStore gc,ClusterStore store,ClusterProfiles profiles,ClusterHttpNodes nodes,JdbcTemplate jdbc,FileGcProperties settings,ClusterProperties cluster){return new ClusterGcCoordinator(gc,store,profiles,nodes,jdbc,settings,cluster);}
    @Bean @ConditionalOnProperty(name="app.storage.files.replication.enabled",havingValue="true") ClusterFileTransfer clusterFileTransfer(JdbcTemplate jdbc,SyncTransactions tx,@org.springframework.beans.factory.annotation.Qualifier("localReplicaStore") you.v50to.eatwhat.service.storage.replication.S3ReplicaObjectStore store,ClusterMember local,you.v50to.eatwhat.service.storage.replication.FilePublicationService publication){return new ClusterFileTransfer(jdbc,tx,store,local,publication);}
    @Bean @ConditionalOnProperty(name="app.cluster.coordinator",havingValue="true") ClusterFileCoordinator clusterFileCoordinator(JdbcTemplate jdbc,ClusterStore membership,ClusterProperties properties){return new ClusterFileCoordinator(jdbc,membership,properties.getNodeCaFile());}
    @Bean @ConditionalOnProperty(name={"app.cluster.coordinator","app.account.enabled"},havingValue="true") ClusterMeshSynchronizer clusterMeshSynchronizer(JdbcTemplate jdbc,you.v50to.eatwhat.account.ConsentStore accounts,ClusterAccountChannel channel,ObjectProvider<ClusterFileCoordinator> files,ClusterStore membership,ClusterProperties properties){return new ClusterMeshSynchronizer(jdbc,accounts,channel,files,membership,properties.getNodeCaFile());}
    @Bean ClusterReplayService clusterReplayService(JdbcTemplate jdbc,SyncTransactions tx,ClusterMember local,you.v50to.eatwhat.sync.DeliveryService delivery,you.v50to.eatwhat.sync.InboxService inbox,you.v50to.eatwhat.sync.EventCodec codec,ObjectProvider<you.v50to.eatwhat.sync.transport.EventPolicy> policies,ObjectProvider<you.v50to.eatwhat.sync.file.FileSyncWorker> files,ObjectProvider<you.v50to.eatwhat.account.AccountSyncWorker> accounts,ClusterProperties cluster,org.springframework.core.env.Environment environment){
        if(cluster.isSingleSiteOnly()&&(local.allocationSlot()!=0||!cluster.isCoordinator()||environment.getProperty("app.sync.capture-enabled",Boolean.class,false)||environment.getProperty("app.sync.transport-enabled",Boolean.class,false)||environment.getProperty("app.account.enabled",Boolean.class,false)))throw new ClusterFailure(503,"INVALID_SINGLE_SITE_CONFIGURATION");
        return new ClusterReplayService(jdbc,tx,local,delivery,inbox,codec,policies.orderedStream().toList(),files,accounts).singleSiteOnly(cluster.isSingleSiteOnly());}
    @Bean @Primary ClusterAccountChannel clusterAccountChannel(JdbcTemplate jdbc,ClusterProperties properties,ControlProperties control,SiteProperties site,ObjectProvider<ClusterProfiles> profiles){return new ClusterAccountChannel(jdbc,properties,control,site,profiles);}
    @Bean @ConditionalOnProperty(name="app.account.enabled",havingValue="true") ClusterChallengePeers clusterChallengePeers(JdbcTemplate jdbc,SiteProperties site,ClusterAccountChannel channel){return new ClusterChallengePeers(site,new ClusterMembershipView(jdbc),channel);}
    @Bean ClusterMember clusterLocalMember(SiteProperties site,ControlProperties control,ClusterProperties cluster,org.springframework.core.env.Environment environment){
        if(!control.isEnabled()||!control.isNodeEnabled()||!java.util.Set.of("isolated-acceptance","managed-deployment").contains(control.getEnvironmentKind())||site.getClusterId()==null||site.getAllocationSlot()==null)throw new ClusterFailure(503,"ISOLATED_CLUSTER_NODE_REQUIRED");
        if(cluster.isCoordinator()&&!environment.getProperty("app.control.independent.enabled",Boolean.class,false)
                &&(site.getMode()!=SiteProperties.Mode.CAMPUS||site.getAllocationSlot()!=0))throw new ClusterFailure(503,"CLUSTER_COORDINATOR_IDENTITY_REQUIRED");
        return new ClusterMember(site.getId(),site.getInstanceId(),site.getMode().name(),site.getAllocationSlot());
    }
    @Bean ClusterNodeAgent clusterNodeAgent(JdbcTemplate jdbc,SyncTransactions tx,SiteProperties site,ClusterMember local,ObjectProvider<ClusterNodeAgent.DataPlane> data){
        var adapter=data.getIfAvailable(()->new ClusterNodeAgent.DataPlane(){public boolean ready(){return false;}public void verifyCatchup(ClusterMembershipCoordinator.Plan plan,ClusterMember member){throw new ClusterFailure(409,"CLUSTER_DATA_PLANE_NOT_READY");}});
        return new ClusterNodeAgent(jdbc,tx,site.getClusterId(),local,adapter);
    }
    @Bean ClusterGcParticipant clusterGcParticipant(JdbcTemplate jdbc,SyncTransactions tx,ClusterMember local,FileGcProperties settings){return new ClusterGcParticipant(jdbc,tx,local,settings);}
    @Bean @ConditionalOnProperty(name="app.cluster.coordinator",havingValue="true")
    ClusterProfiles clusterProfiles(ClusterProperties properties){return new ClusterProfiles(properties);}
    @Bean @ConditionalOnProperty(name={"app.cluster.coordinator","app.cluster.control-lease.enabled"},havingValue="true")
    ClusterControlLease clusterControlLease(JdbcTemplate jdbc,SiteProperties site,ClusterProperties settings){return new ClusterControlLease(jdbc,site.getClusterId(),settings.getControlLease().getDurationSeconds(),settings.getControlLease().isCandidate(),settings.getControlLease().isAutomaticTakeover(),settings.getControlLease().getControllerId());}
    @Bean @ConditionalOnProperty(name={"app.cluster.coordinator","app.cluster.control-lease.enabled"},havingValue="true")
    ClusterControlHandoff clusterControlHandoff(JdbcTemplate jdbc,SyncTransactions tx,ClusterControlLease lease,you.v50to.eatwhat.control.DeploymentExecutor deployment,ClusterProperties properties){return new ClusterControlHandoff(jdbc,tx,lease,deployment,properties.getControlLease().getControllerName());}
    @Bean @ConditionalOnProperty(name={"app.cluster.coordinator","app.cluster.control-lease.enabled"},havingValue="true")
    ClusterDeploymentFence clusterDeploymentFence(JdbcTemplate jdbc,SyncTransactions tx,ClusterControlLease lease,you.v50to.eatwhat.control.DeploymentExecutor deployment){var fence=new ClusterDeploymentFence(jdbc,tx,lease,deployment.stateIdentity());deployment.externalIdleGuard(()->fence.active().isEmpty()?null:"DEPLOYMENT_RECONCILIATION_REQUIRED");return fence;}
    @Bean(initMethod="initialize") @ConditionalOnProperty(name="app.cluster.coordinator",havingValue="true")
    ClusterStore clusterStore(JdbcTemplate jdbc,SyncTransactions tx,SiteProperties site,ClusterMember local,ObjectProvider<ClusterControlLease> lease,ClusterProperties settings,
            ObjectProvider<you.v50to.eatwhat.control.independent.IndependentControlProperties> independent){
        var p=independent.getIfAvailable();var authority=p==null?local.identity():new you.v50to.eatwhat.sync.transport.NodeIdentity(p.getAuthoritySiteId(),p.getAuthorityInstanceId());
        return new ClusterStore(jdbc,tx,site.getClusterId(),authority,lease.getIfAvailable()).singleSiteOnly(settings.isSingleSiteOnly());
    }
    @Bean @ConditionalOnProperty(name="app.cluster.coordinator",havingValue="true")
    ClusterHttpNodes clusterHttpNodes(SiteProperties site,ObjectProvider<ClusterHttpNodes.Synchronizer> synchronizer,ClusterStore membership,ClusterReplayService replay,ClusterProperties properties){return new ClusterHttpNodes(site.getClusterId(),synchronizer.getIfAvailable(()->replay::synchronizeSingleSite),membership,properties.getNodeCaFile());}
    @Bean @ConditionalOnProperty(name="app.cluster.coordinator",havingValue="true")
    ClusterMembershipCoordinator clusterMembershipCoordinator(SiteProperties site,ClusterStore store,ClusterProfiles profiles,ClusterHttpNodes nodes){return new ClusterMembershipCoordinator(site.getClusterId(),store,profiles,nodes);}
    @Bean @ConditionalOnProperty(name="app.cluster.coordinator",havingValue="true")
    ClusterGcStore clusterGcStore(JdbcTemplate jdbc,SyncTransactions tx,ClusterStore store){return new ClusterGcStore(jdbc,tx,store);}
}
