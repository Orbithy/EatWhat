package you.v50to.eatwhat.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import you.v50to.eatwhat.config.sync.SyncConfigurationValidator;
import you.v50to.eatwhat.sync.preferences.*;
import static org.assertj.core.api.Assertions.assertThat;

class PreferenceSyncConfigurationTest {
    private ApplicationContextRunner enabled() {
        return new ApplicationContextRunner().withInitializer(new SyncConfigurationValidator()).withPropertyValues(
                "app.sync.preferences.enabled=true", "app.sync.preferences.peer-site-id=external-test", "app.sync.peer-site-id=external-test",
                "app.site.mode=CAMPUS", "app.site.id=campus-test", "app.site.instance-id=00000000-0000-4000-8000-000000000001",
                "app.account.enabled=true", "app.account.policy-version=cross-border-preferences-v3");
    }
    @Test void disabledByDefault() {
        new ApplicationContextRunner().withInitializer(new SyncConfigurationValidator()).withUserConfiguration(PreferenceSyncConfiguration.class)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(PreferenceCommands.class).doesNotHaveBean(PreferenceEventHandler.class));
    }
    @Test void enabledDomainConstructsItsCommandsPolicyAndInboundHandlerWithoutCircularDependencies() {
        var site=new SiteProperties();site.setId("campus-test");site.setMode(SiteProperties.Mode.CAMPUS);
        site.setInstanceId(java.util.UUID.fromString("00000000-0000-4000-8000-000000000001"));
        var consent=org.mockito.Mockito.mock(you.v50to.eatwhat.account.ConsentStore.class,org.mockito.Mockito.withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        org.mockito.Mockito.when(consent.configuredPolicyVersion()).thenReturn("cross-border-preferences-v3");
        org.mockito.Mockito.when(consent.policyVersion()).thenReturn("administrator-published-v4");
        enabled().withUserConfiguration(PreferenceSyncConfiguration.class)
                .withBean(SiteProperties.class,()->site)
                .withBean(you.v50to.eatwhat.account.ConsentStore.class,()->consent)
                .withBean(org.springframework.jdbc.core.JdbcTemplate.class,()->org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class))
                .withBean(you.v50to.eatwhat.sync.SyncTransactions.class,()->org.mockito.Mockito.mock(you.v50to.eatwhat.sync.SyncTransactions.class,org.mockito.Mockito.withSettings().mockMaker(org.mockito.MockMakers.INLINE)))
                .withBean(you.v50to.eatwhat.sync.OutboxService.class,()->org.mockito.Mockito.mock(you.v50to.eatwhat.sync.OutboxService.class,org.mockito.Mockito.withSettings().mockMaker(org.mockito.MockMakers.INLINE)))
                .run(context->assertThat(context).hasNotFailed().hasSingleBean(PreferenceCommands.class)
                        .hasSingleBean(PreferenceEvents.class).hasSingleBean(PreferenceEventHandler.class));
    }
    @Test void consentAndMatchingSitePass() { enabled().run(context -> assertThat(context).hasNotFailed()); }
    @ParameterizedTest @ValueSource(strings = {"app.site.mode=LEGACY", "app.account.enabled=false", "app.sync.preferences.enabled=maybe",
            "app.sync.preferences.peer-site-id=campus-test", "app.sync.peer-site-id=other-test", "app.sync.preferences.batch-size=0",
            "app.sync.preferences.batch-size=129", "app.account.policy-version=cross-border-v1"})
    void invalidConfigurationFailsWithoutLeakingValues(String property) {
        enabled().withPropertyValues(property).run(context -> assertThat(context.getStartupFailure())
                .hasMessageContaining("app.sync.preferences").hasMessageNotContaining("other-test").hasCause(null));
    }
}
