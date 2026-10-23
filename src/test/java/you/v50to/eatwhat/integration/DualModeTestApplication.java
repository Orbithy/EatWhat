package you.v50to.eatwhat.integration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.mail.javamail.JavaMailSender;
import tools.jackson.databind.ObjectMapper;
import work.foofish.smsverify.SmsTemplate;
import you.v50to.eatwhat.EatwhatApplication;
import you.v50to.eatwhat.service.storage.ObjectStorageService;
import you.v50to.eatwhat.service.storage.S3CompatibleStorageService;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Test-classpath entry point: each invocation owns a JVM and its own Sa-Token globals. */
public final class DualModeTestApplication {
    static final String READY_PREFIX = "EATWHAT_DUAL_MODE_READY=";

    private DualModeTestApplication() {
    }

    public static void main(String[] args) {
        var application = new SpringApplication(EatwhatApplication.class, ExternalBoundaryStubs.class);
        var context = application.run(args);
        try {
            publishReadiness(context);
        } catch (RuntimeException | Error failure) {
            context.close();
            throw failure;
        }
    }

    private static void publishReadiness(ConfigurableApplicationContext context) {
        ConfigurableEnvironment environment = context.getEnvironment();
        environment.getPropertySources().forEach(source -> {
            if (source.getName().contains("application.yml") || source.getName().contains(".env")) {
                throw new IllegalStateException("Host configuration must not enter an isolated child JVM");
            }
        });
        ObjectStorageService storage = context.getBean(ObjectStorageService.class);
        if (!(storage instanceof S3CompatibleStorageService)) {
            throw new IllegalStateException("Dual-mode tests require the real S3 storage service");
        }
        verifyNoInteractions(context.getBean(JavaMailSender.class), context.getBean(SmsTemplate.class));
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        // These URLs contain only synthetic credentials. Signing performs no object-store request.
        var ready = Map.of(
                "port", port,
                "pid", ProcessHandle.current().pid(),
                "mode", environment.getRequiredProperty("app.site.mode"),
                "siteId", environment.getRequiredProperty("app.site.id"),
                "provider", environment.getRequiredProperty("app.storage.s3.provider"),
                "publicUrl", storage.signGetUrl("avatar/probe.png"),
                "privateUrl", storage.signGetUrl("foods/probe.png"));
        System.out.println(READY_PREFIX + context.getBean(ObjectMapper.class).writeValueAsString(ready));
        System.out.flush();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ExternalBoundaryStubs {
        @Bean
        JavaMailSender isolatedMailSender() {
            return mock(JavaMailSender.class);
        }

        @Bean
        SmsTemplate isolatedSmsTemplate() {
            return mock(SmsTemplate.class);
        }
    }
}
