package you.v50to.eatwhat.service.storage.replication;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The CDN invalidation worker is off unless app.storage.files.cdn-invalidation.enabled=true. */
class FileCdnInvalidationSwitchTest {
    private static final String SWITCH = "app.storage.files.cdn-invalidation.enabled";

    @Test
    void everyWorkerClassIsConditionalOnTheSingleSwitch() {
        for (Class<?> type : new Class<?>[]{FileCdnInvalidationStore.class, FileCdnInvalidationWorker.class,
                FileCdnInvalidationRunner.class, FileCdnInvalidationConfiguration.class}) {
            ConditionalOnProperty condition = type.getAnnotation(ConditionalOnProperty.class);
            assertEquals(SWITCH, condition.name()[0], type.getSimpleName());
            assertEquals("true", condition.havingValue(), type.getSimpleName());
        }
    }

    @Test
    void nothingIsCreatedByDefaultOrWhenTheGcOrInventoryIsEnabled() {
        new ApplicationContextRunner()
                .withPropertyValues("app.storage.files.gc.enabled=true", "app.storage.files.gc.inventory-enabled=true")
                .withUserConfiguration(FileCdnInvalidationStore.class, FileCdnInvalidationWorker.class,
                        FileCdnInvalidationRunner.class, FileCdnInvalidationConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FileCdnInvalidationStore.class);
                    assertThat(context).doesNotHaveBean(FileCdnInvalidationWorker.class);
                    assertThat(context).doesNotHaveBean(FileCdnInvalidationRunner.class);
                    assertThat(context).doesNotHaveBean(CdnInvalidationProvider.class);
                });
    }
}
