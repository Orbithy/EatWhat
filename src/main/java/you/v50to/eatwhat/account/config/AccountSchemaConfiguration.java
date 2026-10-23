package you.v50to.eatwhat.account.config;

import org.flywaydb.core.api.callback.Callback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import you.v50to.eatwhat.account.ConsentSchemaMigrationCallback;
import you.v50to.eatwhat.config.SiteProperties;

/** Additive schema permissions only. This never enables account processing or a network listener. */
@Configuration(proxyBeanMethods = false)
public class AccountSchemaConfiguration {
    @Bean public Callback consentSchemaMigrationCallback(SiteProperties site, Environment environment) {
        return new ConsentSchemaMigrationCallback(site, environment.getProperty("spring.datasource.username"),environment.getProperty("app.account.multiwriter.enabled",Boolean.class,false));
    }
}
