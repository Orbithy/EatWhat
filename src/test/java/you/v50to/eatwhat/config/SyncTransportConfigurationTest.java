package you.v50to.eatwhat.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import you.v50to.eatwhat.config.sync.SyncConfigurationValidator;
import you.v50to.eatwhat.config.sync.SyncTransportConfiguration;
import you.v50to.eatwhat.config.sync.SyncTransportRuntime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class SyncTransportConfigurationTest {
    @TempDir Path temporary;
    private ApplicationContextRunner configured;

    @BeforeEach void settings() throws Exception {
        Path placeholder = temporary.resolve("not-loaded-by-early-validation.p12");
        Files.writeString(placeholder, "synthetic");
        configured = new ApplicationContextRunner().withInitializer(new SyncConfigurationValidator())
                .withPropertyValues("app.sync.transport-enabled=true", "app.sync.synthetic-only=true",
                        "app.site.mode=CAMPUS", "app.site.id=campus-test",
                        "app.site.instance-id=00000000-0000-4000-8000-000000000001",
                        "app.sync.peer-site-id=external-test", "app.sync.peer-instance-id=00000000-0000-4000-8000-000000000002",
                        "app.sync.peer-base-url=https://localhost:9443", "app.sync.peer-certificate-sha256=" + "a".repeat(64),
                        "app.sync.key-store=" + placeholder, "app.sync.trust-store=" + placeholder,
                        "app.sync.key-store-password=synthetic-private-marker", "app.sync.trust-store-password=synthetic-private-marker");
    }

    @Test void transportDefaultsRegisterNoListenerClientOrScheduler() {
        new ApplicationContextRunner().withUserConfiguration(SyncTransportConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(SyncTransportRuntime.class);
                    assertThat(context).doesNotHaveBean("transportMetrics");
                });
    }

    @Test void completeSyntheticCampusAndPassiveExternalPassEarlyValidationWithoutOpeningStoresOrNetwork() {
        configured.run(context -> assertThat(context).hasNotFailed());
        configured.withPropertyValues("app.site.mode=EXTERNAL", "app.sync.peer-base-url=")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.sync.synthetic-only=false", "app.site.mode=LEGACY", "app.sync.peer-site-id=campus-test",
            "app.sync.peer-instance-id=00000000-0000-4000-8000-000000000001", "app.sync.peer-certificate-sha256=private-marker",
            "app.sync.peer-base-url=http://localhost:9443", "app.sync.peer-base-url=https://private-marker@localhost:9443",
            "app.sync.peer-base-url=https://localhost:9443/path", "app.sync.request-timeout=31s",
            "app.sync.request-timeout=1ms", "app.sync.lease-duration=2s", "app.sync.poll-interval=0s",
            "app.sync.retry-base=0s", "app.sync.retry-max=2d", "app.sync.key-store=/missing/synthetic-private-marker",
            "app.sync.peer-instance-id=private-marker", "app.sync.capture-enabled=true"})
    void invalidConfigurationFailsBeforeOtherBeansWithoutExposingRawValues(String property) {
        var started = new AtomicBoolean();
        configured.withPropertyValues(property).withBean(Object.class, () -> { started.set(true); return new Object(); })
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(started).isFalse();
                    assertThat(context.getStartupFailure()).hasMessageContaining("app.sync").hasMessageNotContaining("private-marker");
                    assertThat(context.getStartupFailure().getCause()).isNull();
                });
    }
}
