package you.v50to.eatwhat.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.sync.SyncConfigurationValidator;
import you.v50to.eatwhat.identity.EntityIdentityService;
import you.v50to.eatwhat.sync.OutboxService;
import you.v50to.eatwhat.sync.SyncTransactions;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.catalog.CatalogCommands;
import you.v50to.eatwhat.sync.catalog.CatalogEventHandler;
import you.v50to.eatwhat.sync.catalog.CatalogEventPolicy;
import you.v50to.eatwhat.sync.catalog.CatalogProjection;
import you.v50to.eatwhat.sync.catalog.CatalogStore;
import you.v50to.eatwhat.sync.catalog.CatalogSyncConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CatalogSyncConfigurationTest {
    private static final String[] VALID = {
            "app.sync.catalog.enabled=true", "app.sync.catalog.peer-site-id=external-test",
            "app.sync.peer-site-id=external-test", "app.site.mode=CAMPUS", "app.site.id=campus-test",
            "app.site.instance-id=00000000-0000-4000-8000-000000000001", "app.account.enabled=true"
    };

    @Test
    void catalogServicesAreAbsentByDefault() {
        new ApplicationContextRunner()
                .withInitializer(new SyncConfigurationValidator())
                .withUserConfiguration(CatalogSyncConfiguration.class)
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(CatalogCommands.class)
                        .doesNotHaveBean(CatalogEventPolicy.class)
                        .doesNotHaveBean(CatalogEventHandler.class));
    }

    @Test
    void aCompleteExplicitDeclarationRegistersBothEventHandlersAndCommands() {
        new ApplicationContextRunner()
                .withInitializer(new SyncConfigurationValidator())
                .withUserConfiguration(CatalogSyncConfiguration.class, TestDependencies.class)
                .withPropertyValues(VALID)
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(CatalogCommands.class)
                        .hasSingleBean(CatalogEventPolicy.class)
                        .hasBean("catalogActionEventHandler")
                        .hasBean("catalogStateBridgeEventHandler")
                        .getBeans(CatalogEventHandler.class).hasSize(2));
    }

    @Test
    void catalogMustUseTheSameRemotePeerAsGlobalSyncAndRequireAccountConsent() {
        new ApplicationContextRunner()
                .withInitializer(new SyncConfigurationValidator())
                .withUserConfiguration(CatalogSyncConfiguration.class, TestDependencies.class)
                .withPropertyValues(VALID)
                .withPropertyValues("app.sync.peer-site-id=other-test")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("app.sync.catalog.enabled");
                });

        new ApplicationContextRunner()
                .withInitializer(new SyncConfigurationValidator())
                .withUserConfiguration(CatalogSyncConfiguration.class, TestDependencies.class)
                .withPropertyValues(VALID)
                .withPropertyValues("app.account.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("app.sync.catalog.enabled");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDependencies {
        @Bean SiteProperties siteProperties() {
            SiteProperties site = new SiteProperties();
            site.setMode(SiteProperties.Mode.CAMPUS);
            site.setId("campus-test");
            site.setInstanceId(java.util.UUID.fromString("00000000-0000-4000-8000-000000000001"));
            return site;
        }

        @Bean DriverManagerDataSource dataSource() {
            return new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/catalog-test", "test", "test");
        }
        @Bean JdbcTemplate jdbcTemplate(DriverManagerDataSource dataSource) { return new JdbcTemplate(dataSource); }
        @Bean PlatformTransactionManager transactionManager(DriverManagerDataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
        @Bean SyncTransactions syncTransactions(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
            return new SyncTransactions(jdbc, transactionManager);
        }
        @Bean OutboxService outboxService(JdbcTemplate jdbc, SyncTransactions transactions) {
            return new OutboxService(jdbc, transactions, new EventCodec(), siteProperties());
        }
        @Bean EntityIdentityService entityIdentityService(JdbcTemplate jdbc) {
            return new EntityIdentityService(jdbc);
        }
        @Bean ConsentStore consentStore(JdbcTemplate jdbc, SyncTransactions transactions) {
            return new ConsentStore(jdbc, transactions, siteProperties(),
                    "catalog-test-policy", java.time.Duration.ofMinutes(5), java.time.Duration.ofMinutes(2),
                    "external-test", java.util.UUID.fromString("00000000-0000-4000-8000-000000000002"));
        }
        @Bean CatalogProjection catalogProjection(JdbcTemplate jdbc, CatalogStore store,
                                                   EntityIdentityService identities, SiteProperties site) {
            return new CatalogProjection(jdbc, store, identities, site);
        }
    }
}
