package you.v50to.eatwhat.cluster;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Load balancers can remove a site that cannot validate fresh business sessions. */
@Component("clusterAuth")
@ConditionalOnProperty(name="app.cluster.enabled",havingValue="true")
public final class ClusterAuthHealth implements HealthIndicator {
    private final ClusterSessions sessions;private final ClusterProperties settings;private final ObjectProvider<ClusterAuthStore> store;
    public ClusterAuthHealth(ClusterSessions sessions,ClusterProperties settings,ObjectProvider<ClusterAuthStore> store){this.sessions=sessions;this.settings=settings;this.store=store;}
    @Override public Health health(){try{
        if(!sessions.active())return Health.down().withDetail("code","CLUSTER_SITE_NOT_ACTIVE").build();
        if(sessions.independentAuthentication())return sessions.localAuthenticationHealthy()?Health.up().withDetail("mode","LOCAL_MULTIWRITER").build():Health.down().withDetail("code","LOCAL_AUTH_SCHEMA_UNAVAILABLE").build();
        if(settings.isCoordinator())return Health.up().withDetail("mode","AUTHORITY").build();
        var auth=store.getIfAvailable();if(auth==null)return Health.unknown().withDetail("mode","AUTHORITY_RPC").build();
        auth.verify();return Health.up().withDetail("mode","SHARED_AUTH_STORE").build();
    }catch(RuntimeException unavailable){return Health.down().withDetail("code","CLUSTER_AUTH_STORE_UNAVAILABLE").build();}}
}
