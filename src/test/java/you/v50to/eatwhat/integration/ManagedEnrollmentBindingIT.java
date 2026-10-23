package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import you.v50to.eatwhat.config.identity.GlobalIdentityBindingVerifier;
import you.v50to.eatwhat.config.identity.GlobalIdentityConfiguration;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Real retained PostgreSQL identity: quarantine cannot be used to downgrade a joined installation. */
class ManagedEnrollmentBindingIT extends ClusterMembershipIT {
    private MockEnvironment pending(){return new MockEnvironment().withProperty("app.control.enrollment-required","true")
        .withProperty("app.control.enabled","true").withProperty("app.control.node-enabled","true")
        .withProperty("app.control.environment-kind","managed-deployment").withProperty("app.cluster.enabled","false");}
    @Test void onlyNewUnjoinedBindingMayStartInManagedQuarantine(){
        var node=database(external,true);var role="\""+node.runtimeUsername()+"\"";
        node.admin().execute("GRANT USAGE ON SCHEMA eatwhat_cluster TO "+role);
        node.admin().execute("GRANT SELECT ON eatwhat_cluster.state,eatwhat_cluster.member,eatwhat_cluster.change,eatwhat_cluster.local_state,eatwhat_cluster.local_change TO "+role);
        var config=new GlobalIdentityConfiguration();
        assertDoesNotThrow(()->config.globalIdentityBindingVerifier(node.site(),node.runtime(),pending()).run(null));
        assertThrows(IllegalStateException.class,()->new GlobalIdentityBindingVerifier(node.site(),node.runtime(),false).run(null));
        for(String key:new String[]{"app.account.enabled","app.sync.capture-enabled","app.sync.transport-enabled"})
            assertThrows(IllegalStateException.class,()->config.globalIdentityBindingVerifier(node.site(),node.runtime(),pending().withProperty(key,"true")).run(null));
        assertThrows(IllegalStateException.class,()->config.globalIdentityBindingVerifier(node.site(),node.runtime(),pending().withProperty("app.control.environment-kind","isolated-acceptance")).run(null));
        var original=node.site().getInstanceId();node.site().setInstanceId(UUID.randomUUID());
        assertThrows(IllegalStateException.class,()->config.globalIdentityBindingVerifier(node.site(),node.runtime(),pending()).run(null));node.site().setInstanceId(original);
        node.admin().update("INSERT INTO eatwhat_cluster.local_state(singleton,cluster_id,epoch,site_id,instance_id,state,members) VALUES(true,?,0,?,?,'JOINING','[]'::jsonb)",cluster,external.siteId(),external.instanceId());
        assertThrows(IllegalStateException.class,()->config.globalIdentityBindingVerifier(node.site(),node.runtime(),pending()).run(null));
    }
    @Test void existingAuthorityCannotDisableClusterEvenWithQuarantineFlag(){
        assertThrows(IllegalStateException.class,()->new GlobalIdentityConfiguration().globalIdentityBindingVerifier(db.site(),db.runtime(),pending()).run(null));
    }
}
