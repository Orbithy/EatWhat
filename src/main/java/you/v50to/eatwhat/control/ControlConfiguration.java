package you.v50to.eatwhat.control;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.config.SiteProperties;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="app.control.enabled",havingValue="true")
@EnableConfigurationProperties(ControlProperties.class)
public class ControlConfiguration {
    @Bean ControlAudit controlAudit(ControlProperties p) {
        if(!"isolated-acceptance".equals(p.getEnvironmentKind()) && !"managed-deployment".equals(p.getEnvironmentKind()))throw new ControlFailure(503,"ISOLATED_ACCEPTANCE_REQUIRED");
        return new ControlAudit(p.getStateDirectory(),p.isSharedStateRequired());
    }
    @Bean(destroyMethod="close") DeploymentExecutor deploymentExecutor(ControlProperties p,ControlAudit audit){return new DeploymentExecutor(p,audit);}
    @Bean ControlService controlService(ControlProperties p,ControlAudit audit,DeploymentExecutor deployment){return new ControlService(p,audit,deployment);}
    @Bean(destroyMethod="close") ControlDeploymentRecovery controlDeploymentRecovery(ControlProperties p,ControlAudit audit,ControlServerStore files,DeploymentExecutor deployment){return new ControlDeploymentRecovery(p,audit,files,deployment);}
    @Bean(destroyMethod="close") ControlRuns controlRuns(ControlProperties p,ControlAudit audit,ControlService service){return new ControlRuns(p,audit,service);}
    @Bean(destroyMethod="close") @ConditionalOnProperty(name="app.control.node-enabled",havingValue="true")
    ControlHealthChecks controlHealthChecks(JdbcTemplate jdbc,org.springframework.data.redis.core.StringRedisTemplate redis,org.springframework.boot.availability.ApplicationAvailability availability,you.v50to.eatwhat.config.S3StorageProperties props){return new ControlHealthChecks(jdbc,redis,availability,props);}
    @Bean(destroyMethod="close") @ConditionalOnProperty(name="app.control.node-enabled",havingValue="true")
    ControlStorageCheck controlStorageCheck(ControlAudit audit,SiteProperties site,you.v50to.eatwhat.config.S3StorageProperties props){return new ControlStorageCheck(audit,site,props);}
    @Bean(destroyMethod="close") @ConditionalOnProperty(name="app.control.node-enabled",havingValue="true")
    ControlNode controlNode(ControlProperties p,JdbcTemplate jdbc,SiteProperties site,ControlAudit audit,ControlHealthChecks health,ControlStorageCheck storage){
        if(site.getMode()==SiteProperties.Mode.LEGACY || site.getId()==null || site.getInstanceId()==null)throw new ControlFailure(503,"EXPLICIT_NODE_IDENTITY_REQUIRED");
        return new ControlNode(p,jdbc,site,audit,health,storage);
    }
}
