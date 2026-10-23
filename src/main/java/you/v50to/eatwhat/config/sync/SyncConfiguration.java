package you.v50to.eatwhat.config.sync;

import org.flywaydb.core.api.callback.Callback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.*;

/** Explicit internal operations only: no domain capture, HTTP endpoint, scheduler or network client. */
@Configuration(proxyBeanMethods = false)
public class SyncConfiguration {
    @Bean
    public EventCodec eventCodec() { return new EventCodec(); }

    @Bean
    public SyncTransactions syncTransactions(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new SyncTransactions(jdbc, manager);
    }

    @Bean
    public Callback syncSchemaMigrationCallback(SiteProperties site, Environment environment) {
        return new SyncSchemaMigrationCallback(site, environment.getProperty("spring.datasource.username"));
    }

    @Bean
    public OutboxService outboxService(JdbcTemplate jdbc, SyncTransactions transactions, EventCodec codec, SiteProperties site) {
        return new OutboxService(jdbc, transactions, codec, site);
    }

    @Bean
    public DeliveryService deliveryService(JdbcTemplate jdbc, SyncTransactions transactions, EventCodec codec) {
        return new DeliveryService(jdbc, transactions, codec);
    }

    @Bean
    public EventHandlerRegistry eventHandlerRegistry(ObjectProvider<EventHandler> handlers) {
        return new EventHandlerRegistry(handlers.orderedStream().toList());
    }

    @Bean
    public InboxService inboxService(JdbcTemplate jdbc, SyncTransactions transactions, EventCodec codec, EventHandlerRegistry handlers) {
        return new InboxService(jdbc, transactions, codec, handlers);
    }

    @Bean
    public CommandIdempotencyService commandIdempotencyService(JdbcTemplate jdbc, SyncTransactions transactions, SiteProperties site) {
        return new CommandIdempotencyService(jdbc, transactions, site);
    }

    @Bean
    public ManagedDomainWriteGuard managedDomainWriteGuard(JdbcTemplate jdbc) {
        return new ManagedDomainWriteGuard(jdbc);
    }
}
