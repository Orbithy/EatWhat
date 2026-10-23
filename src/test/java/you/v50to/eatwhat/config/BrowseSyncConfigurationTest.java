package you.v50to.eatwhat.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import you.v50to.eatwhat.config.sync.SyncConfigurationValidator;
import you.v50to.eatwhat.sync.browse.*;
import static org.assertj.core.api.Assertions.assertThat;

class BrowseSyncConfigurationTest {
    private ApplicationContextRunner enabled() {
        return new ApplicationContextRunner().withInitializer(new SyncConfigurationValidator()).withPropertyValues(
                "app.sync.browse.enabled=true", "app.sync.browse.peer-site-id=external-test", "app.sync.peer-site-id=external-test",
                "app.site.mode=CAMPUS", "app.site.id=campus-test", "app.site.instance-id=00000000-0000-4000-8000-000000000001",
                "app.account.enabled=true", "app.account.policy-version=cross-border-browse-v2");
    }
    @Test void disabledByDefault() {
        new ApplicationContextRunner().withInitializer(new SyncConfigurationValidator()).withUserConfiguration(BrowseSyncConfiguration.class)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(BrowseCommands.class).doesNotHaveBean(BrowseEventHandler.class));
    }
    @Test void consentAndMatchingSitePass() { enabled().run(context -> assertThat(context).hasNotFailed()); }
    @ParameterizedTest @ValueSource(strings = {"app.site.mode=LEGACY", "app.account.enabled=false", "app.sync.browse.enabled=maybe",
            "app.sync.browse.peer-site-id=campus-test", "app.sync.peer-site-id=other-test", "app.sync.browse.batch-size=0",
            "app.sync.browse.batch-size=129", "app.account.policy-version=cross-border-v1"})
    void invalidConfigurationFailsWithoutLeakingValues(String property) {
        enabled().withPropertyValues(property).run(context -> assertThat(context.getStartupFailure())
                .hasMessageContaining("app.sync.browse").hasMessageNotContaining("other-test").hasCause(null));
    }
}
