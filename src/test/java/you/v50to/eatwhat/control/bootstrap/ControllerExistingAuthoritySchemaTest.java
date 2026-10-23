package you.v50to.eatwhat.control.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import you.v50to.eatwhat.config.SiteProperties;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class ControllerExistingAuthoritySchemaTest {
    static SiteProperties site(){
        var site=new SiteProperties();site.setMode(SiteProperties.Mode.CAMPUS);site.setId("campus");
        site.setClusterId(UUID.randomUUID());site.setInstanceId(UUID.randomUUID());site.setAllocationSlot(0);return site;
    }
    static MockEnvironment environment(){
        var e=new MockEnvironment();
        for(String name:List.of("app.control.enabled","app.control.node-enabled","app.control.shared-state-required",
                "app.cluster.enabled","app.cluster.coordinator","spring.flyway.enabled"))e.setProperty(name,"true");
        e.setProperty("app.control.environment-kind","managed-deployment");e.setProperty("app.cluster.single-site-only","false");
        e.setProperty("app.control.database-mode","existing-authority");e.setProperty("app.control.authority-database-fingerprint","c".repeat(32));
        e.setProperty("spring.datasource.url","jdbc:postgresql://127.0.0.1:25432/synthetic");
        e.setProperty("spring.datasource.username","synthetic_app");e.setProperty("spring.datasource.password","synthetic-private-password");return e;
    }
    @Test void ordinaryBusinessAndExistingControllerModesKeepDefaultMigrationBehavior(){
        for(String[] values:new String[][]{{},{"app.control.enabled=true"},{"app.control.database-mode=migrate"}})
            new ApplicationContextRunner().withUserConfiguration(ControllerExistingAuthoritySchema.class).withPropertyValues(values)
                    .run(c->assertThat(c).hasNotFailed().doesNotHaveBean(FlywayMigrationStrategy.class));
    }
    @Test void explicitManagedSharedAuthorityGetsTheValidationStrategyWithoutOpeningDatabase(){
        new ApplicationContextRunner().withUserConfiguration(ControllerExistingAuthoritySchema.class)
                .withBean(SiteProperties.class,ControllerExistingAuthoritySchemaTest::site)
                .withInitializer(c->c.getEnvironment().getPropertySources().addFirst(environment().getPropertySources().get("mockProperties")))
                .run(c->assertThat(c).hasNotFailed().hasSingleBean(FlywayMigrationStrategy.class));
    }
    @Test void modeCannotDisableFlywayOrApplyToAnUnsharedUnboundBusinessProcess(){
        for(String name:List.of("app.control.enabled","app.control.node-enabled","app.control.shared-state-required",
                "app.cluster.enabled","app.cluster.coordinator","spring.flyway.enabled")){
            var e=environment().withProperty(name,"false");assertThrows(IllegalStateException.class,()->ControllerExistingAuthoritySchema.admission(e,site()));
        }
        var s=site();s.setAllocationSlot(1);assertThrows(IllegalStateException.class,()->ControllerExistingAuthoritySchema.admission(environment(),s));
        var e=environment().withProperty("app.cluster.single-site-only","true");assertThrows(IllegalStateException.class,()->ControllerExistingAuthoritySchema.admission(e,site()));
    }
    @Test void alternateMigrationCredentialsAndMissingFingerprintAreRejectedWithoutEchoingSecrets(){
        for(String name:List.of("spring.flyway.url","spring.flyway.user","spring.flyway.password","app.control.authority-database-fingerprint")){
            var e=environment().withProperty(name,"synthetic-secret-not-in-errors");
            var failure=assertThrows(IllegalStateException.class,()->ControllerExistingAuthoritySchema.admission(e,site()));
            assertEquals("EXISTING_AUTHORITY_SCHEMA_REQUIRED",failure.getMessage());assertNull(failure.getCause());
        }
    }
}
