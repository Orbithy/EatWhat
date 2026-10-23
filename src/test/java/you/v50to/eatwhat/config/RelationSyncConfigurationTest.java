package you.v50to.eatwhat.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import you.v50to.eatwhat.config.sync.SyncConfigurationValidator;
import you.v50to.eatwhat.sync.relation.RelationCommands;
import you.v50to.eatwhat.sync.relation.RelationEventHandler;
import you.v50to.eatwhat.sync.relation.RelationSyncConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class RelationSyncConfigurationTest {
    private static ApplicationContextRunner enabled() {
        return new ApplicationContextRunner().withInitializer(new SyncConfigurationValidator())
                .withPropertyValues("app.sync.relation.enabled=true", "app.sync.relation.peer-site-id=external-test",
                        "app.sync.peer-site-id=external-test", "app.site.mode=CAMPUS", "app.site.id=campus-test",
                        "app.site.instance-id=00000000-0000-4000-8000-000000000001", "app.account.enabled=true");
    }

    @Test void relationDomainIsOffByDefault() {
        new ApplicationContextRunner().withInitializer(new SyncConfigurationValidator())
                .withUserConfiguration(RelationSyncConfiguration.class).run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(RelationCommands.class)
                            .doesNotHaveBean(RelationEventHandler.class);
                });
    }

    @Test void explicitSiteMatchingPeerAndConsentDeclarationPasses() {
        enabled().run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.site.mode=LEGACY", "app.sync.relation.peer-site-id=campus-test",
            "app.sync.relation.peer-site-id=", "app.sync.peer-site-id=other-test", "app.account.enabled=false",
            "app.sync.relation.enabled=maybe"})
    void invalidOrUngatedRelationDeclarationFailsEarlyWithoutLeakingValues(String property) {
        enabled().withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.sync.relation")
                    .hasMessageNotContaining("other-test");
            assertThat(context.getStartupFailure().getCause()).isNull();
        });
    }
}
