package you.v50to.eatwhat.account.config;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import you.v50to.eatwhat.config.SiteProperties;

/** Shape validation runs before database/Redis connections; actual PKCS12 validation happens later. */
public final class AccountConfigurationValidator implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override public void initialize(ConfigurableApplicationContext context) {
        try {
            Binder binder = Binder.get(context.getEnvironment());
            if (!binder.bind("app.account.enabled", Boolean.class).orElse(false)) return;
            var properties = binder.bind("app.account", AccountProperties.class).orElseGet(AccountProperties::new);
            var site=binder.bind("app.site", SiteProperties.class).orElseGet(SiteProperties::new);
            properties.validate(site);
            if(site.getClusterId()!=null&&!binder.bind("app.cluster.enabled",Boolean.class).orElse(false))throw new IllegalArgumentException("Cluster account channel must be enabled");
            if (!binder.bind("app.cluster.enabled",Boolean.class).orElse(false)
                    && binder.bind("app.sync.transport-enabled", Boolean.class).orElse(false)
                    && properties.getListenPort() == binder.bind("app.sync.listen-port", Integer.class).orElse(9443)) {
                throw new IllegalArgumentException("Account and event listeners must use separate ports");
            }
        } catch (RuntimeException failure) {
            // A Binder exception can contain a submitted password or URL. Never retain that cause.
            throw new IllegalStateException("app.account.enabled requires valid site, cluster or bilateral channel, consent policy and bounded timing settings");
        }
    }
}
