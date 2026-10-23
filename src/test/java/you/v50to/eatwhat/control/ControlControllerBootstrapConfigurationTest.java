package you.v50to.eatwhat.control;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.cluster.*;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.SyncTransactions;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Spring dependency/opt-in wiring only. Mock services are never executed. */
class ControlControllerBootstrapConfigurationTest {
    static <T>T stub(Class<T> type){return mock(type,withSettings().mockMaker(org.mockito.MockMakers.INLINE));}
    @Test void disabledByDefaultAndWithoutControlPlane(){
        for(String[] properties:new String[][]{{},{"app.control.enabled=true"},{"app.control.controller-bootstrap.enabled=true"}})
            new ApplicationContextRunner().withUserConfiguration(ControlControllerBootstrapConfiguration.class).withPropertyValues(properties)
                    .run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(ControlBootstrapAdmission.class).isEmpty());assertTrue(c.getBeansOfType(ControlBootstrapInspection.class).isEmpty());});
    }
    @Test void explicitOptInWiresEveryInternalAdmissionAndClaimServiceWithoutRunningIt(){
        var p=new ControlProperties();p.setEnabled(true);p.getControllerBootstrap().setEnabled(true);
        new ApplicationContextRunner().withUserConfiguration(ControlControllerBootstrapConfiguration.class)
                .withPropertyValues("app.control.enabled=true","app.control.controller-bootstrap.enabled=true")
                .withBean(ControlProperties.class,()->p).withBean(SiteProperties.class,SiteProperties::new).withBean(ClusterProperties.class,ClusterProperties::new)
                .withBean(JdbcTemplate.class,()->stub(JdbcTemplate.class)).withBean(SyncTransactions.class,()->stub(SyncTransactions.class))
                .withBean(ClusterControlLease.class,()->stub(ClusterControlLease.class)).withBean(DeploymentExecutor.class,()->stub(DeploymentExecutor.class))
                .withBean(ControlServerStore.class,()->stub(ControlServerStore.class)).withBean(ControlServers.class,()->stub(ControlServers.class))
                .withBean(ControlSsh.class,()->stub(ControlSsh.class)).withBean(ControlBootstrapSources.class,()->stub(ControlBootstrapSources.class))
                .withBean(ControlBootstrapJournal.class,()->stub(ControlBootstrapJournal.class))
                .run(c->{assertNull(c.getStartupFailure());assertNotNull(c.getBean(ControlBootstrapArtifacts.class));assertNotNull(c.getBean(ControlBootstrapPreflights.class));
                    assertNotNull(c.getBean(ControlBootstrapAdmission.class));assertNotNull(c.getBean(ControlBootstrapPhaseClaims.class));
                    assertNotNull(c.getBean(ControlBootstrapNativeInputs.class));assertNotNull(c.getBean(ControlBootstrapNativePhases.class));assertNotNull(c.getBean(ControlBootstrapDependencyPhases.class));assertNotNull(c.getBean(ControlBootstrapInspection.class));
                    assertNotNull(c.getBean(ControlBootstrapControllerOperations.class));assertNotNull(c.getBean(ControlBootstrapControllerPhases.class));
                    assertNotNull(c.getBean(ControlBootstrapTargetMaterials.class));
                    assertNotNull(c.getBean(ControlBootstrapTargetNetwork.class));assertNotNull(c.getBean(ControlBootstrapControllerFactory.class));
                    assertNotNull(c.getBean(ControlBootstrapBusinessAllocation.class));
                    assertNotNull(c.getBean(ControlBootstrapBusinessFactory.class));assertNotNull(c.getBean(ControlBootstrapBusinessOperations.class));assertNotNull(c.getBean(ControlBootstrapBusinessPhases.class));
                    assertNotNull(c.getBean(ControlBootstrapMemberRelay.class));assertNotNull(c.getBean(ControlBootstrapMemberAuth.class));assertNotNull(c.getBean(ControlBootstrapMemberIngress.class));assertNotNull(c.getBean(ControlBootstrapMemberRuntime.class));assertNotNull(c.getBean(ControlBootstrapProfilePlans.class));
                    assertNotNull(c.getBean(ControlBootstrapProfileState.class));assertNotNull(c.getBean(ControlBootstrapProfileReceiver.class));assertNotNull(c.getBean(ControlBootstrapProfilePhases.class));assertNotNull(c.getBean(ControlBootstrapMembership.class));
                    assertNotNull(c.getBean(ControlBootstrapVerify.class));
                    assertNotNull(c.getBean(ControlBootstrapPlans.class));assertNotNull(c.getBean(ControlBootstrapWorkflow.class));assertTrue(c.getBean(ControlBootstrapWorker.class).isRunning());
                    verifyNoInteractions(c.getBean(ControlSsh.class));var jdbc=c.getBean(JdbcTemplate.class);
                    verify(jdbc).afterPropertiesSet();verifyNoMoreInteractions(jdbc);});
    }
}
