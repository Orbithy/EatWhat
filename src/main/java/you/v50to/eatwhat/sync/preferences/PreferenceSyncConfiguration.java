package you.v50to.eatwhat.sync.preferences;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="app.sync.preferences.enabled",havingValue="true")
@EnableConfigurationProperties(PreferenceSyncProperties.class)
public class PreferenceSyncConfiguration {
    @Bean public PreferenceEvents preferenceEventPolicy(ConsentStore consent){return new PreferenceEvents(consent);}
    @Bean public PreferenceEventHandler preferenceEventHandler(JdbcTemplate jdbc,ConsentStore consent,PreferenceCommands commands){return new PreferenceEventHandler(jdbc,consent,commands);}
    @Bean public PreferenceCommands preferenceCommands(JdbcTemplate jdbc,SyncTransactions transactions,OutboxService outbox,
            ConsentStore consent,SiteProperties site,PreferenceSyncProperties properties) {
        properties.validate(site,properties.getPeerSiteId());properties.validatePolicy(consent.configuredPolicyVersion());
        return new PreferenceCommands(jdbc,transactions,outbox,consent,site,properties.getPeerSiteId(),properties.getBatchSize());
    }
}
