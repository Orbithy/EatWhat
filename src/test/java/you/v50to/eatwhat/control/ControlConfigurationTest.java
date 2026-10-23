package you.v50to.eatwhat.control;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class ControlConfigurationTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false) static class PrivateFiles {
        @org.springframework.context.annotation.Bean ControlServerStore files(ControlAudit audit){return new ControlServerStore(audit);}
    }
    @Test void managedObserverCanStartWithDeploymentDisabledAndNoSshFiles(){new ApplicationContextRunner().withUserConfiguration(ControlConfiguration.class,PrivateFiles.class).withPropertyValues("app.control.enabled=true","app.control.environment-kind=managed-deployment","app.control.state-directory="+root.resolve("state")).run(c->{assertThat(c).hasNotFailed();assertThat(c.getBean(DeploymentExecutor.class).enabled()).isFalse();assertThat(c.getBean(ControlRuns.class).suites()).allMatch(s->s.get("enabled").equals(false));});}
    @Test void requiredSharedMountIsBoundAndFailsBeforeCreatingFallback(){var state=root.resolve("missing-nfs");new ApplicationContextRunner().withUserConfiguration(ControlConfiguration.class,PrivateFiles.class).withPropertyValues("app.control.enabled=true","app.control.environment-kind=managed-deployment","app.control.shared-state-required=true","app.control.state-directory="+state).run(c->{assertThat(c).hasFailed();assertThat(java.nio.file.Files.exists(state)).isFalse();});}
    @Test void defaultsDoNotCreateControlOrProxyBeans(){new ApplicationContextRunner().withUserConfiguration(ControlConfiguration.class,ControlController.class,ControlNodeController.class,ControlExceptionHandler.class,ControlResponseHeaders.class).run(c->assertThat(c).hasNotFailed().doesNotHaveBean(ControlAudit.class).doesNotHaveBean(ControlRuns.class).doesNotHaveBean(ControlNode.class).doesNotHaveBean(ControlController.class).doesNotHaveBean(ControlNodeController.class).doesNotHaveBean(ControlExceptionHandler.class).doesNotHaveBean(ControlResponseHeaders.class));}
    @Test void productionCannotEnableControl(){new ApplicationContextRunner().withUserConfiguration(ControlConfiguration.class).withPropertyValues("app.control.enabled=true","app.control.environment-kind=production").run(c->assertThat(c).hasFailed());}
}
