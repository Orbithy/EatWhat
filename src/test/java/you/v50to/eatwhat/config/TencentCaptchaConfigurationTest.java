package you.v50to.eatwhat.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.exception.BizException;
import you.v50to.eatwhat.service.TencentCaptchaService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TencentCaptchaConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesBinding.class, TencentCaptchaService.class);

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(TencentCaptchaProperties.class)
    static class PropertiesBinding { }

    @Test void bindsExternalConfigurationWithoutExposingAnySecret() {
        runner.withPropertyValues("app.captcha.tencent.app-id=123456",
                "app.captcha.tencent.app-secret-key=synthetic-app-secret",
                "app.captcha.tencent.secret-id=synthetic-api-id",
                "app.captcha.tencent.secret-key=synthetic-api-secret",
                "app.captcha.tencent.session-token=synthetic-api-session",
                "app.captcha.tencent.timeout=3s",
                "app.captcha.tencent.aid-encrypted-ttl=90s").run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(TencentCaptchaProperties.class);
            assertThat(properties.getAppId()).isEqualTo(123456L);
            assertThat(properties.getTimeout()).isEqualTo(java.time.Duration.ofSeconds(3));
            assertThat(properties.getAidEncryptedTtl()).isEqualTo(java.time.Duration.ofSeconds(90));
            assertThat(properties.getSessionToken()).isEqualTo("synthetic-api-session");
            var config = context.getBean(TencentCaptchaService.class).publicConfig();
            assertThat(config.enabled()).isTrue();
            assertThat(config.appId()).isEqualTo("123456");
            assertThat(config.aidEncrypted()).isNotBlank();
            assertThat(config.aidEncryptedType()).isEqualTo("cbc");
            assertThat(config.expiresAt()).isBetween(java.time.Instant.now().getEpochSecond() + 88,
                    java.time.Instant.now().getEpochSecond() + 90);
            assertThat(config.toString()).doesNotContain("synthetic-app-secret", "synthetic-api-id", "synthetic-api-secret", "synthetic-api-session");
        });
    }

    @Test void missingConfigurationKeepsAppAvailableAndFailsClosedOnSend() {
        runner.withPropertyValues("app.captcha.tencent.app-id=").run(context -> {
            assertThat(context).hasNotFailed();
            var failure = assertThrows(BizException.class,
                    () -> context.getBean(TencentCaptchaService.class).verify("ticket", "randstr", "203.0.113.7"));
            assertThat(failure.getBizCode()).isEqualTo(BizCode.CAPTCHA_UNAVAILABLE);
        });
    }

    @Test void explicitLocalDisableNeedsNoCredentials() {
        runner.withPropertyValues("app.captcha.tencent.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            context.getBean(TencentCaptchaService.class).verify(null, null, null);
            assertThat(context.getBean(TencentCaptchaService.class).publicConfig().enabled()).isFalse();
        });
    }
}
