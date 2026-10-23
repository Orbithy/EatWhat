package you.v50to.eatwhat.sync.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.OutboxService;
import you.v50to.eatwhat.sync.SyncTransactions;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.sync.notification.enabled", havingValue = "true")
@EnableConfigurationProperties(NotificationReadSyncProperties.class)
public class NotificationReadSyncConfiguration {
    @Bean
    public NotificationReadEvents notificationReadEventPolicy(ConsentStore consent) {
        return new NotificationReadEvents(consent);
    }

    @Bean
    public NotificationReadStore notificationReadStore(JdbcTemplate jdbc) {
        return new NotificationReadStore(jdbc);
    }

    @Bean
    public NotificationReadEventHandler notificationReadEventHandler(JdbcTemplate jdbc,
            NotificationReadStore store, ConsentStore consent) {
        return new NotificationReadEventHandler(jdbc, store, consent);
    }

    @Bean
    public NotificationReadCommands notificationReadCommands(JdbcTemplate jdbc, SyncTransactions transactions,
            OutboxService outbox, NotificationReadStore store, ConsentStore consent, SiteProperties site,
            NotificationReadSyncProperties properties,
            org.springframework.beans.factory.ObjectProvider<NotificationHistoryCommands> history) {
        properties.validate(site);
        var commands=new NotificationReadCommands(jdbc, transactions, outbox, store, consent, site,
                properties.getPeerSiteId());
        commands.setHistory(history.getIfAvailable());return commands;
    }

    @Bean
    public NotificationReadWorker notificationReadWorker(SyncTransactions transactions,
            NotificationReadStore store, NotificationReadCommands commands,
            ConsentStore consent, NotificationReadSyncProperties properties) {
        return new NotificationReadWorker(transactions, store, commands, consent, properties.getWorkerBatchSize());
    }

    @Bean public NotificationHistoryEvents notificationHistoryPolicy(ConsentStore consent) { return new NotificationHistoryEvents(consent); }
    @Bean public NotificationHistoryHandler notificationHistoryHandler(JdbcTemplate jdbc,ConsentStore consent) { return new NotificationHistoryHandler(jdbc,consent); }
    @Bean @ConditionalOnProperty(name="app.sync.notification.historical-backfill-enabled",havingValue="true")
    public NotificationHistoryCommands notificationHistoryCommands(JdbcTemplate jdbc,SyncTransactions transactions,
            OutboxService outbox,ConsentStore consent,SiteProperties site,NotificationReadSyncProperties properties) {
        return new NotificationHistoryCommands(jdbc,transactions,outbox,consent,site,properties.getPeerSiteId(),properties.getWorkerBatchSize());
    }
    @Bean @ConditionalOnProperty(name="app.sync.notification.historical-backfill-enabled",havingValue="true")
    public NotificationHistoryWorker notificationHistoryWorker(NotificationHistoryCommands commands) { return new NotificationHistoryWorker(commands); }
}
