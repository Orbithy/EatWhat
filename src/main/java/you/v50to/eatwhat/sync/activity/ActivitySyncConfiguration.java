package you.v50to.eatwhat.sync.activity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.identity.EntityIdentityService;
import you.v50to.eatwhat.sync.OutboxService;

/** Capture, handlers and transport policy exist only while the activity domain is explicitly enabled. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.sync.activity.enabled", havingValue = "true")
@EnableConfigurationProperties(ActivitySyncProperties.class)
public class ActivitySyncConfiguration {
    @Bean public ActivityEventPolicy activityEventPolicy() { return new ActivityEventPolicy(); }

    @Bean public ActivityEventCapture activityEventCapture(JdbcTemplate jdbc, OutboxService outbox,
            EntityIdentityService identities, SiteProperties site, ActivitySyncProperties properties,
            ConsentStore consent, org.springframework.beans.factory.ObjectProvider<ActivityDurableCommands> durable) {
        properties.validate(site);
        var capture=new ActivityEventCapture(jdbc, outbox, identities, site, properties.getPeerSiteId(),
                consent::replicationAllowed);
        capture.setDurableCommands(durable.getIfAvailable());
        return capture;
    }

    @Bean public ActivityEventHandler activityFoodCreatedHandler(JdbcTemplate jdbc, ConsentStore consent) {
        return new ActivityEventHandler(ActivityEvents.FOODS, ActivityEvents.CREATED, jdbc, consent::replicationAllowed);
    }

    @Bean public ActivityEventHandler activityFoodDeletedHandler(JdbcTemplate jdbc, ConsentStore consent) {
        return new ActivityEventHandler(ActivityEvents.FOODS, ActivityEvents.DELETED, jdbc, consent::replicationAllowed);
    }

    @Bean public ActivityEventHandler activityDinnerCreatedHandler(JdbcTemplate jdbc, ConsentStore consent) {
        return new ActivityEventHandler(ActivityEvents.DINNERS, ActivityEvents.CREATED, jdbc, consent::replicationAllowed);
    }

    @Bean public ActivityEventHandler activityDinnerDeletedHandler(JdbcTemplate jdbc, ConsentStore consent) {
        return new ActivityEventHandler(ActivityEvents.DINNERS, ActivityEvents.DELETED, jdbc, consent::replicationAllowed);
    }

    @Bean public ActivityEventHandler activityPostCreatedHandler(JdbcTemplate jdbc, ConsentStore consent) {
        return new ActivityEventHandler(ActivityEvents.POSTS, ActivityEvents.CREATED, jdbc, consent::replicationAllowed);
    }

    @Bean public ActivityEventHandler activityPostDeletedHandler(JdbcTemplate jdbc, ConsentStore consent) {
        return new ActivityEventHandler(ActivityEvents.POSTS, ActivityEvents.DELETED, jdbc, consent::replicationAllowed);
    }

    @Bean public ActivityStateEvents activityStatePolicy(ConsentStore consent) { return new ActivityStateEvents(consent); }
    @Bean public ActivityStateHandler activityStateHandler(JdbcTemplate jdbc,ConsentStore consent) { return new ActivityStateHandler(jdbc,consent); }
    @Bean @ConditionalOnProperty(name="app.sync.activity.durable-intents-enabled",havingValue="true")
    public ActivityDurableCommands activityDurableCommands(JdbcTemplate jdbc,you.v50to.eatwhat.sync.SyncTransactions tx,
            OutboxService outbox,ConsentStore consent,SiteProperties site,ActivitySyncProperties properties) {
        return new ActivityDurableCommands(jdbc,tx,outbox,consent,site,properties.getPeerSiteId(),50);
    }
    @Bean @ConditionalOnProperty(name="app.sync.activity.durable-intents-enabled",havingValue="true")
    public ActivityIntentWorker activityIntentWorker(ActivityDurableCommands commands) { return new ActivityIntentWorker(commands); }
}
