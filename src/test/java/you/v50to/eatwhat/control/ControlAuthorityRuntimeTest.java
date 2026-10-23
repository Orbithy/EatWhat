package you.v50to.eatwhat.control;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import you.v50to.eatwhat.account.config.AccountConfigurationValidator;
import you.v50to.eatwhat.cluster.*;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.config.sync.SyncConfigurationValidator;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Rendered activation must pass real production property validators without granting business leadership. */
class ControlAuthorityRuntimeTest {
    final UUID cluster=UUID.randomUUID();
    final ClusterMember authority=new ClusterMember("campus",UUID.randomUUID(),"CAMPUS",0);

    @Test void boundBusinessConfigurationEnablesMemberRoutingButCannotAcquireControlLease(){
        var values=ControlAuthorityRuntime.business(cluster,authority,false);
        new ApplicationContextRunner()
            .withInitializer(new AccountConfigurationValidator())
            .withInitializer(new SyncConfigurationValidator())
            .withPropertyValues(values.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).toArray(String[]::new))
            .run(context->{
                assertNull(context.getStartupFailure());
                var binder=Binder.get(context.getEnvironment());
                var site=binder.bind("app.site",SiteProperties.class).get();
                assertEquals(cluster,site.getClusterId());
                assertEquals(authority.identity().siteId(),site.getId());
                assertEquals(authority.instanceId(),site.getInstanceId());
                var configured=binder.bind("app.cluster",ClusterProperties.class).get();
                assertTrue(configured.isCoordinator());assertFalse(configured.isSingleSiteOnly());
                assertTrue(configured.getControlLease().isEnabled());
                assertFalse(configured.getControlLease().isCandidate());
                assertFalse(configured.getControlLease().isAutomaticTakeover());
                var control=binder.bind("app.control",ControlProperties.class).get();
                assertTrue(context.getEnvironment().getProperty("app.control.enrollment-required",Boolean.class,false));
                assertFalse(control.getDeployment().isEnabled());
                assertFalse(control.getServers().isInstallationEnabled());
                assertFalse(control.getServers().isClusterEnrollmentEnabled());
            });
    }

    @Test void rendererRefusesExternalOrWrongSlotAndMissingClusterBeforeConfigurationCanBeApplied(){
        for(var member:new ClusterMember[]{null,new ClusterMember("external",UUID.randomUUID(),"EXTERNAL",1),new ClusterMember("campus",UUID.randomUUID(),"CAMPUS",1)})
            assertThrows(ControlFailure.class,()->ControlAuthorityRuntime.business(cluster,member,false));
        assertThrows(ControlFailure.class,()->ControlAuthorityRuntime.business(null,authority,false));
        assertThrows(ControlFailure.class,()->ControlAuthorityRuntime.business(new UUID(0,0),authority,false));
        var secured=ControlAuthorityRuntime.business(cluster,authority,true);
        assertEquals("/config/authority-ca.pem",secured.get("app.cluster.node-ca-file"));
        assertThrows(UnsupportedOperationException.class,()->secured.put("app.cluster.control-lease.candidate","true"));
    }
}
