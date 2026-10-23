package you.v50to.eatwhat.sync.privacy;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.identity.EntityIdentityService;
import you.v50to.eatwhat.sync.OutboxService;
import you.v50to.eatwhat.sync.SyncTransactions;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.sync.privacy.enabled", havingValue = "true")
@EnableConfigurationProperties(PrivacySyncProperties.class)
public class PrivacySyncConfiguration {
    @Bean public PrivacyEvents privacyEventPolicy(ConsentStore consent) { return new PrivacyEvents(consent); }
    @Bean public PrivacyStore privacyStore(JdbcTemplate jdbc) { return new PrivacyStore(jdbc); }

    @Bean public PrivacyCommands privacyCommands(JdbcTemplate jdbc, SyncTransactions transactions,
            PrivacyStore store, OutboxService outbox, EntityIdentityService identities, ConsentStore consent,
            SiteProperties site, PrivacySyncProperties properties) {
        properties.validate(site, properties.getPeerSiteId());
        return new PrivacyCommands(jdbc, transactions, store, outbox, identities, consent, site,
                properties.getPeerSiteId());
    }

    @Bean public PrivacyEventHandler privacyEventHandler(JdbcTemplate jdbc, PrivacyStore store,
            EntityIdentityService identities, ConsentStore consent) {
        return new PrivacyEventHandler(jdbc, store, identities, consent);
    }
}
