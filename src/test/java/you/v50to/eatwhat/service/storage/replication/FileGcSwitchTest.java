package you.v50to.eatwhat.service.storage.replication;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * inventory-enabled=true means "report-only inventory"; only app.storage.files.gc.enabled=true may create the GC
 * service, its runner and therefore any candidate detection.
 */
class FileGcSwitchTest {
    @Test
    void gcBeansAreConditionalOnTheSeparateGcSwitchOnly() {
        for (Class<?> type : new Class<?>[]{FileGcService.class, FileGcRunner.class}) {
            ConditionalOnProperty condition = type.getAnnotation(ConditionalOnProperty.class);
            assertEquals("app.storage.files.gc.enabled", condition.name()[0], type.getSimpleName());
            assertEquals("true", condition.havingValue());
            assertEquals(1, condition.name().length);
        }
        for (Class<?> type : new Class<?>[]{FileInventoryService.class, FileInventoryRunner.class}) {
            ConditionalOnProperty condition = type.getAnnotation(ConditionalOnProperty.class);
            assertArrayEquals(new String[]{"app.storage.files.gc.inventory-enabled"}, condition.name(), type.getSimpleName());
        }
    }

    @Test
    void inventoryEnabledAloneCreatesNeitherTheGcServiceNorItsRunner() {
        new ApplicationContextRunner()
                .withPropertyValues("app.storage.files.gc.inventory-enabled=true")
                .withUserConfiguration(FileGcService.class, FileGcRunner.class)
                .run(context -> {
                    // The conditions are evaluated at registration: no bean definition, hence no missing-dependency failure.
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FileGcService.class);
                    assertThat(context).doesNotHaveBean(FileGcRunner.class);
                });
    }

    @Test
    void gcSwitchDefaultsToOffSoNothingIsCreatedWithoutAnyProperty() {
        new ApplicationContextRunner()
                .withUserConfiguration(FileGcService.class, FileGcRunner.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FileGcService.class);
                    assertThat(context).doesNotHaveBean(FileGcRunner.class);
                });
    }
}
