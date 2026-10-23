package you.v50to.eatwhat.sync.file;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.*;
import you.v50to.eatwhat.config.sync.SyncTransportProperties;
import you.v50to.eatwhat.service.storage.replication.*;
import you.v50to.eatwhat.sync.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileSyncConfigurationTest {
    ApplicationContextRunner runner() { return new ApplicationContextRunner().withUserConfiguration(FileSyncConfiguration.class); }
    ApplicationContextRunner dependencies() {
        var site=new SiteProperties();site.setMode(SiteProperties.Mode.CAMPUS);site.setId("campus");site.setInstanceId(UUID.randomUUID());
        var replication=new FileReplicationProperties();replication.setEnabled(true);
        var jdbc=mock(JdbcTemplate.class);var dataSource=mock(javax.sql.DataSource.class);when(jdbc.getDataSource()).thenReturn(dataSource);
        var tx=new SyncTransactions(jdbc,new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        var consent=new ConsentStore(jdbc,tx,site,"p1",java.time.Duration.ofMinutes(5),java.time.Duration.ofMinutes(5),"external",UUID.randomUUID());
        var outbox=new OutboxService(jdbc,tx,new EventCodec(),site);
        return runner().withPropertyValues("app.storage.files.replication.enabled=true").withBean(SiteProperties.class,()->site).withBean(FileReplicationProperties.class,()->replication)
                .withBean(S3StorageProperties.class,S3StorageProperties::new)
                .withBean(JdbcTemplate.class,()->jdbc).withBean(ConsentStore.class,()->consent)
                .withBean(OutboxService.class,()->outbox).withBean(SyncTransactions.class,()->tx)
                .withBean(FileReplicationEnqueuer.class,()->mock(FileReplicationEnqueuer.class))
                .withBean(FileReplicaCatalog.class,()->mock(FileReplicaCatalog.class)).withBean(FilePublicationService.class,()->mock(FilePublicationService.class));
    }
    @Test void featureDefaultsOffWithoutCreatingHandlersPublishersOrPolicies() {
        runner().run(c->{assertNull(c.getStartupFailure());assertEquals(0,c.getBeansOfType(FileEventHandler.class).size());assertEquals(0,c.getBeansOfType(FileSyncRuntime.class).size());});
    }
    @Test void enabledRequiresRealDomainTransportAndConsentDependencies() {
        dependencies().withPropertyValues("app.sync.files.enabled=true").run(c->assertNotNull(c.getStartupFailure()));
    }
    @Test void validExplicitOptInRegistersAllFourFamiliesAndStartsAndStopsPublisher() {
        final FileSyncRuntime[] runtime=new FileSyncRuntime[1];
        dependencies().withPropertyValues("app.sync.files.enabled=true","app.sync.transport-enabled=true","app.sync.synthetic-only=false",
                "app.sync.peer-site-id=external","app.sync.peer-instance-id="+UUID.randomUUID()).run(c->{
            assertNull(c.getStartupFailure());assertEquals(4,c.getBeansOfType(FileEventHandler.class).size());
            runtime[0]=c.getBean(FileSyncRuntime.class);assertTrue(runtime[0].isRunning());
        });
        assertFalse(runtime[0].isRunning());
    }
    @Test void peerIdentityAndPollingBoundsAreValidated() {
        var p=new FileSyncProperties();var s=new SiteProperties();s.setMode(SiteProperties.Mode.CAMPUS);s.setId("c");s.setInstanceId(UUID.randomUUID());
        var t=new SyncTransportProperties();t.setTransportEnabled(true);t.setSyntheticOnly(false);t.setPeerSiteId("x");t.setPeerInstanceId(UUID.randomUUID());
        assertDoesNotThrow(()->p.validate(s,t,true));p.setBatchSize(0);assertThrows(IllegalArgumentException.class,()->p.validate(s,t,true));
        p.setBatchSize(32);t.setPeerSiteId("c");assertThrows(IllegalArgumentException.class,()->p.validate(s,t,true));
    }
}
