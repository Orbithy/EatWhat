package you.v50to.eatwhat.control;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.env.Environment;
import you.v50to.eatwhat.cluster.*;
import you.v50to.eatwhat.config.SiteProperties;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="app.control.enabled",havingValue="true")
public class ControlServerConfiguration {
    @Bean ControlServerStore controlServerStore(ControlAudit audit){return new ControlServerStore(audit);}
    @Bean ControlSsh controlSsh(ControlServerStore store,ControlProperties settings){return new ControlSsh(store,settings);}
    @Bean ControlRegisteredDeployments controlRegisteredDeployments(ControlServerStore store,ControlSsh ssh,ControlProperties settings,SiteProperties site,ObjectProvider<ClusterStore> membership,ObjectProvider<ClusterDeploymentFence> fence,DeploymentExecutor deployment){var transport=new ControlRegisteredDeployments(store,ssh,settings,site,membership,fence);deployment.registeredTransport(transport);return transport;}
    @Bean ControlServers controlServers(ControlServerStore store,ControlSsh ssh,DeploymentExecutor deployment,ControlAudit audit,SiteProperties site,ObjectProvider<ClusterProperties> cluster,ObjectProvider<ClusterControlLease> lease,ControlProperties settings){return new ControlServers(store,ssh,deployment,audit,site,cluster,lease,new ControlCredentials(settings));}
    @Bean ControlBootstrapJournal controlBootstrapJournal(ControlServerStore store,ControlAudit audit,DeploymentExecutor deployment,ControlServers servers){return new ControlBootstrapJournal(store,audit,deployment,servers);}
    @Bean ControlBootstrapSources controlBootstrapSources(ControlServerStore store,ControlServers servers,ControlSsh ssh){return new ControlBootstrapSources(store,servers,ssh);}
    @Bean(destroyMethod="close") ControlInstallExecutor controlInstallExecutor(ControlServerStore store,ControlSsh ssh,ControlServers servers,ControlProperties settings,ControlAudit audit,Environment environment,JdbcTemplate jdbc,ObjectProvider<ClusterStore> membership){return new ControlInstallExecutor(store,ssh,servers,settings,audit,environment,jdbc,membership);}
    @Bean ControlRemoteEnrollment controlRemoteEnrollment(ControlServerStore store,ControlSsh ssh,ControlServers servers,DeploymentExecutor deployment,ControlProperties settings,ControlAudit audit,SiteProperties site,JdbcTemplate jdbc,ObjectProvider<ClusterStore> membership,ObjectProvider<ClusterProfiles> profiles,ObjectProvider<ClusterMembershipCoordinator> coordinator,ObjectProvider<ClusterProperties> cluster,ObjectProvider<ClusterHttpNodes> nodes){return new ControlRemoteEnrollment(store,ssh,servers,deployment,settings,audit,site,jdbc,membership,profiles,coordinator,cluster,nodes);}
    @Bean ControlClusterEnrollment controlClusterEnrollment(ControlServerStore store,ControlSsh ssh,ControlServers servers,DeploymentExecutor deployment,ControlProperties settings,ControlAudit audit,SiteProperties site,JdbcTemplate jdbc,ObjectProvider<ClusterStore> membership,ObjectProvider<ClusterMembershipCoordinator> coordinator,ObjectProvider<ClusterProperties> cluster,ControlRemoteEnrollment remote){return new ControlClusterEnrollment(store,ssh,servers,deployment,settings,audit,site,jdbc,membership,coordinator,cluster).remote(remote);}
}
