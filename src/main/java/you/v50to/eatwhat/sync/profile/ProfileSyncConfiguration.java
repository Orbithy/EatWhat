package you.v50to.eatwhat.sync.profile;

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
@ConditionalOnProperty(name = "app.sync.profile.enabled", havingValue = "true")
@EnableConfigurationProperties(ProfileSyncProperties.class)
public class ProfileSyncConfiguration {
    @Bean public ProfileEvents profileEventPolicy(ConsentStore consent) { return new ProfileEvents(consent); }
    @Bean public ProfileStore profileStore(JdbcTemplate jdbc) { return new ProfileStore(jdbc); }

    @Bean public ProfileCommands profileCommands(JdbcTemplate jdbc, SyncTransactions transactions,
            ProfileStore store, OutboxService outbox, EntityIdentityService identities, ConsentStore consent,
            SiteProperties site, ProfileSyncProperties properties,
            org.springframework.beans.factory.ObjectProvider<you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts> accounts) {
        properties.validate(site, properties.getPeerSiteId());
        var commands=new ProfileCommands(jdbc, transactions, store, outbox, identities, consent, site,
                properties.getPeerSiteId());
        commands.setMultiwriter(properties.isMultiwriterEnabled(),accounts.getIfAvailable());
        return commands;
    }

    @Bean public ProfileEventHandler profileEventHandler(JdbcTemplate jdbc, ProfileStore store,
            EntityIdentityService identities, ConsentStore consent, SiteProperties site) {
        return new ProfileEventHandler(jdbc, store, identities, consent, site);
    }

    @Bean public ProfileEventHandler profileV2Handler(JdbcTemplate jdbc,ProfileStore store,
            EntityIdentityService identities,ConsentStore consent,SiteProperties site,
            org.springframework.beans.factory.ObjectProvider<you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts> accounts) {
        return new ProfileEventHandler(jdbc,store,identities,consent,site,2,accounts.getIfAvailable());
    }

    @Bean public ProfileConflictService profileConflictService(JdbcTemplate jdbc, ProfileStore store,
            ProfileCommands commands, ConsentStore consent, OutboxService outbox, SiteProperties site,
            ProfileSyncProperties properties) {
        return new ProfileConflictService(jdbc, store, commands, consent, outbox, site, properties.getPeerSiteId());
    }
}
