package you.v50to.eatwhat.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import you.v50to.eatwhat.config.sync.SyncConfigurationValidator;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class SyncConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withInitializer(new SyncConfigurationValidator());

    @Test
    void foundationDefaultsNeverEnableCaptureOrTransport() {
        runner.run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("app.sync.capture-enabled=false", "app.sync.transport-enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.sync.capture-enabled", "app.sync.transport-enabled"})
    void accidentalActivationFailsBeforeAnyConnection(String flag) {
        var contacted = new AtomicBoolean();
        runner.withPropertyValues(flag + "=true").withBean(Object.class, () -> {
            contacted.set(true); return new Object();
        }).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining(flag);
            assertThat(contacted).isFalse();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.sync.capture-enabled", "app.sync.transport-enabled"})
    void invalidFlagDoesNotEchoUntrustedValue(String flag) {
        runner.withPropertyValues(flag + "=private-marker")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining(flag).hasMessageNotContaining("private-marker");
                    assertThat(context.getStartupFailure().getCause()).isNull();
                });
    }
}
