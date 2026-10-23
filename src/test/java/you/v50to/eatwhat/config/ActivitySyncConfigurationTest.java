package you.v50to.eatwhat.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import you.v50to.eatwhat.config.sync.SyncConfigurationValidator;
import you.v50to.eatwhat.sync.activity.ActivityEventCapture;
import you.v50to.eatwhat.sync.activity.ActivitySyncConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class ActivitySyncConfigurationTest {
    private static ApplicationContextRunner enabled() {
        return new ApplicationContextRunner().withInitializer(new SyncConfigurationValidator())
                .withPropertyValues("app.sync.activity.enabled=true", "app.sync.activity.peer-site-id=external-test",
                        "app.site.mode=CAMPUS", "app.site.id=campus-test",
                        "app.site.instance-id=00000000-0000-4000-8000-000000000001", "app.account.enabled=true");
    }

    @Test void theDomainIsOffByDefaultAndRegistersNoCaptureOrHandler() {
        new ApplicationContextRunner().withUserConfiguration(ActivitySyncConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(ActivityEventCapture.class);
            assertThat(context.getBeanNamesForType(you.v50to.eatwhat.sync.activity.ActivityEventHandler.class)).isEmpty();
        });
        new ApplicationContextRunner().withInitializer(new SyncConfigurationValidator())
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test void aCompleteDeclarationPassesEarlyValidationAndMayCarryDomainTransport() {
        enabled().run(context -> assertThat(context).hasNotFailed());
        // Domain events need the explicit opt-out of synthetic-only; capture alone does not.
        enabled().withPropertyValues("app.sync.transport-enabled=true", "app.sync.synthetic-only=false")
                .withPropertyValues(transportSettings()).run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.site.mode=LEGACY", "app.sync.activity.peer-site-id=campus-test", "app.sync.activity.peer-site-id=",
            "app.sync.activity.peer-site-id=bad site!", "app.account.enabled=false", "app.sync.activity.enabled=maybe"})
    void incompleteOrUngatedDeclarationsFailBeforeAnyBeanWithoutRawValues(String property) {
        enabled().withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.sync.activity").hasMessageNotContaining("bad site!");
            assertThat(context.getStartupFailure().getCause()).isNull();
        });
    }

    @Test void domainTransportStillRequiresAnEnabledDomain() {
        new ApplicationContextRunner().withInitializer(new SyncConfigurationValidator())
                .withPropertyValues("app.site.mode=CAMPUS", "app.site.id=campus-test",
                        "app.site.instance-id=00000000-0000-4000-8000-000000000001")
                .withPropertyValues("app.sync.transport-enabled=true", "app.sync.synthetic-only=false")
                .withPropertyValues(transportSettings())
                .run(context -> assertThat(context).hasFailed());
    }

    private static String[] transportSettings() {
        return new String[]{"app.sync.peer-site-id=external-test", "app.sync.peer-instance-id=00000000-0000-4000-8000-000000000002",
                "app.sync.peer-base-url=https://localhost:9443", "app.sync.peer-certificate-sha256=" + "a".repeat(64),
                "app.sync.key-store=" + placeholder(), "app.sync.trust-store=" + placeholder(),
                "app.sync.key-store-password=synthetic", "app.sync.trust-store-password=synthetic"};
    }

    private static String placeholder() {
        try {
            var file = java.nio.file.Files.createTempFile("activity-config", ".p12");
            file.toFile().deleteOnExit();
            java.nio.file.Files.writeString(file, "synthetic");
            return file.toString();
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
}
