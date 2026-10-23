package you.v50to.eatwhat.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import you.v50to.eatwhat.account.AccountAuthService;
import you.v50to.eatwhat.account.config.AccountConfiguration;
import you.v50to.eatwhat.account.config.AccountConfigurationValidator;
import you.v50to.eatwhat.account.config.AccountRuntime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class AccountConfigurationTest {
    @TempDir Path temporary;
    private ApplicationContextRunner configured;

    @BeforeEach void setup() throws Exception {
        Path file = temporary.resolve("synthetic-placeholder.p12");
        Files.writeString(file, "early-validation-does-not-load-TLS");
        configured = new ApplicationContextRunner().withInitializer(new AccountConfigurationValidator())
                .withPropertyValues("app.account.enabled=true", "app.account.synthetic-only=true",
                        "app.site.mode=CAMPUS", "app.site.id=campus-test",
                        "app.site.instance-id=00000000-0000-4000-8000-000000000001",
                        "app.account.peer-site-id=external-test",
                        "app.account.peer-instance-id=00000000-0000-4000-8000-000000000002",
                        "app.account.peer-base-url=https://localhost:9444",
                        "app.account.peer-certificate-sha256=" + "a".repeat(64),
                        "app.account.key-store=" + file, "app.account.trust-store=" + file,
                        "app.account.key-store-password=private-test-marker",
                        "app.account.trust-store-password=private-test-marker");
    }

    @Test void disabledDefaultsCreateNoAccountChannelGateOrWorker() {
        new ApplicationContextRunner().withUserConfiguration(AccountConfiguration.class)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(AccountRuntime.class)
                        .doesNotHaveBean(AccountAuthService.class).doesNotHaveBean("accountChannel"));
    }

    @Test void bothSyntheticSitesAcceptDedicatedOnlineChannelConfigurationWithoutOpeningAnything() {
        configured.run(context -> assertThat(context).hasNotFailed());
        configured.withPropertyValues("app.site.mode=EXTERNAL").run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.account.enabled=private-test-marker", "app.account.synthetic-only=false",
            "app.site.mode=LEGACY", "app.account.peer-site-id=campus-test",
            "app.account.peer-instance-id=00000000-0000-4000-8000-000000000001",
            "app.account.peer-certificate-sha256=private-test-marker", "app.account.peer-base-url=http://localhost:9444",
            "app.account.peer-base-url=https://private-test-marker@localhost:9444", "app.account.peer-base-url=https://localhost/rpc",
            "app.account.key-store=/missing/private-test-marker", "app.account.authorization-ttl=301s",
            "app.account.authorization-ttl=0s", "app.account.proof-ttl=1h", "app.account.policy-version=bad version",
            "app.account.listen-host=0.0.0.999", "app.account.listen-port=443", "app.account.request-timeout=11s",
            "app.account.lease-duration=1s", "app.account.poll-interval=0s"})
    void invalidSettingsFailBeforeDependencyBeansWithoutEchoingSecrets(String property) {
        var initialized = new AtomicBoolean();
        configured.withPropertyValues(property).withBean(Object.class, () -> { initialized.set(true); return new Object(); })
                .run(context -> {
                    assertThat(context).hasFailed(); assertThat(initialized).isFalse();
                    assertThat(context.getStartupFailure()).hasMessageContaining("app.account.enabled")
                            .hasMessageNotContaining("private-test-marker");
                    assertThat(context.getStartupFailure().getCause()).isNull();
                });
    }

    @Test void accountAndEventListenerCannotAccidentallyShareTheirPort() {
        configured.withPropertyValues("app.sync.transport-enabled=true", "app.sync.listen-port=9444")
                .run(context -> assertThat(context).hasFailed());
    }
}
