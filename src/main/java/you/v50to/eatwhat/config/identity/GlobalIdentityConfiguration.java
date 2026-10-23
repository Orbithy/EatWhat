package you.v50to.eatwhat.config.identity;

import org.flywaydb.core.api.callback.Callback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.config.SiteProperties;

@Configuration(proxyBeanMethods = false)
public class GlobalIdentityConfiguration {
    @Bean
    public Callback globalIdentityMigrationCallback(SiteProperties site, Environment environment) {
        return new GlobalIdentityMigrationCallback(site, environment.getProperty("spring.datasource.username"));
    }

    @Bean
    public GlobalIdentityBindingVerifier globalIdentityBindingVerifier(SiteProperties site, JdbcTemplate jdbc,Environment environment) {
        boolean pending=environment.getProperty("app.control.enrollment-required",Boolean.class,false)
                && environment.getProperty("app.control.enabled",Boolean.class,false)
                && environment.getProperty("app.control.node-enabled",Boolean.class,false)
                && "managed-deployment".equals(environment.getProperty("app.control.environment-kind"))
                && !environment.getProperty("app.account.enabled",Boolean.class,false)
                && !environment.getProperty("app.sync.capture-enabled",Boolean.class,false)
                && !environment.getProperty("app.sync.transport-enabled",Boolean.class,false);
        return new GlobalIdentityBindingVerifier(site, jdbc,environment.getProperty("app.cluster.enabled",Boolean.class,false),pending);
    }
}
