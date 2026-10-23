package you.v50to.eatwhat.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import you.v50to.eatwhat.controller.ProfileConflictController;
import you.v50to.eatwhat.sync.notification.NotificationReadSyncConfiguration;
import you.v50to.eatwhat.sync.privacy.PrivacySyncConfiguration;
import you.v50to.eatwhat.sync.profile.ProfileSyncConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class ProfilePrivacyNotificationSyncConfigurationTest {
    @Test
    void allNewUserDomainsRemainAbsentByDefaultAndWhenExplicitlyDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProfileSyncConfiguration.class,
                        PrivacySyncConfiguration.class,
                        NotificationReadSyncConfiguration.class,
                        ProfileConflictController.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertProfileDomainAbsent(context);
                    assertPrivacyDomainAbsent(context);
                    assertNotificationDomainAbsent(context);
                });

        new ApplicationContextRunner()
                .withUserConfiguration(ProfileSyncConfiguration.class,
                        PrivacySyncConfiguration.class,
                        NotificationReadSyncConfiguration.class,
                        ProfileConflictController.class)
                .withPropertyValues("app.sync.profile.enabled=false",
                        "app.sync.privacy.enabled=false",
                        "app.sync.notification.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertProfileDomainAbsent(context);
                    assertPrivacyDomainAbsent(context);
                    assertNotificationDomainAbsent(context);
                });
    }

    private static void assertProfileDomainAbsent(org.springframework.context.ApplicationContext context) {
        assertThat(context.getBeanNamesForType(you.v50to.eatwhat.sync.profile.ProfileCommands.class)).isEmpty();
        assertThat(context.getBeanNamesForType(you.v50to.eatwhat.sync.profile.ProfileEventHandler.class)).isEmpty();
        assertThat(context.getBeanNamesForType(you.v50to.eatwhat.sync.profile.ProfileConflictService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ProfileConflictController.class)).isEmpty();
    }

    private static void assertPrivacyDomainAbsent(org.springframework.context.ApplicationContext context) {
        assertThat(context.getBeanNamesForType(you.v50to.eatwhat.sync.privacy.PrivacyCommands.class)).isEmpty();
        assertThat(context.getBeanNamesForType(you.v50to.eatwhat.sync.privacy.PrivacyEventHandler.class)).isEmpty();
    }

    private static void assertNotificationDomainAbsent(org.springframework.context.ApplicationContext context) {
        assertThat(context.getBeanNamesForType(you.v50to.eatwhat.sync.notification.NotificationReadCommands.class)).isEmpty();
        assertThat(context.getBeanNamesForType(you.v50to.eatwhat.sync.notification.NotificationReadEventHandler.class)).isEmpty();
        assertThat(context.getBeanNamesForType(you.v50to.eatwhat.sync.notification.NotificationReadWorker.class)).isEmpty();
    }
}
