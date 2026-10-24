package you.v50to.eatwhat.sync.browse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.sync.browse.enabled", havingValue = "true")
@EnableConfigurationProperties(BrowseSyncProperties.class)
public class BrowseSyncConfiguration {
    @Bean public BrowseEvents browseEventPolicy(ConsentStore consent) { return new BrowseEvents(consent); }
    @Bean public BrowseEventHandler browseEventHandler(JdbcTemplate jdbc, ConsentStore consent) { return new BrowseEventHandler(jdbc, consent); }
    @Bean public BrowseEventHandler browseEventHandlerV2(JdbcTemplate jdbc, ConsentStore consent) { return new BrowseEventHandler(jdbc, consent, 2); }
    @Bean public BrowseClearEventHandler browseClearEventHandler(JdbcTemplate jdbc, ConsentStore consent) { return new BrowseClearEventHandler(jdbc, consent); }
    @Bean public BrowseCommands browseCommands(JdbcTemplate jdbc, SyncTransactions transactions,
            OutboxService outbox, ConsentStore consent, SiteProperties site, BrowseSyncProperties properties) {
        properties.validate(site, properties.getPeerSiteId());
        properties.validatePolicy(consent.configuredPolicyVersion());
        return new BrowseCommands(jdbc, transactions, outbox, consent, site, properties.getPeerSiteId(), properties.getBatchSize());
    }
}
