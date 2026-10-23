package you.v50to.eatwhat.service.storage.replication;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import you.v50to.eatwhat.config.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Exercise production @Bean names/conditions with real SDK clients; construction sends no requests. */
class FileReplicationWiringTest {
    ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(ReplicationStorageConfiguration.class,FileReplicationWorker.class);
    }
    ApplicationContextRunner configured(boolean worker) {
        var local=new S3StorageProperties();local.setEndpoint("http://127.0.0.1:9");local.setRegion("us-east-1");
        local.setBucket("synthetic-local");local.setAccessKey("synthetic-local-key");local.setSecretKey("synthetic-local-secret");
        var replication=new FileReplicationProperties();replication.setEnabled(true);replication.setWorkerEnabled(worker);
        if(worker) {
            var peer=replication.getPeer();peer.setEndpoint("http://127.0.0.1:9");peer.setRegion("us-east-1");
            peer.setBucket("synthetic-peer");peer.setAccessKey("synthetic-peer-key");peer.setSecretKey("synthetic-peer-secret");
        }
        return runner().withPropertyValues("app.storage.files.replication.enabled=true","app.storage.files.replication.worker-enabled="+worker)
                .withBean(S3StorageProperties.class,()->local).withBean(FileReplicationProperties.class,()->replication)
                .withBean(FileTransferJobStore.class,()->mock(FileTransferJobStore.class))
                .withBean(ReplicationConsentGate.class,()->mock(ReplicationConsentGate.class));
    }
    @Test void defaultsCreateNoStoresOrCopyWorker() {
        runner().run(c->{assertThat(c).hasNotFailed();assertThat(c).doesNotHaveBean(ReplicaObjectStore.class);assertThat(c).doesNotHaveBean(FileReplicationWorker.class);});
    }
    @Test void metadataOnlySiteCreatesLocalStoreWithoutPeerCredentialsOrCopyWorker() {
        configured(false).run(c->{
            assertThat(c).hasNotFailed();assertThat(c).hasSingleBean(S3ReplicaObjectStore.class);
            assertThat(c).hasBean("localReplicaStore");assertThat(c).doesNotHaveBean("peerReplicaStore");
            assertThat(c).doesNotHaveBean(FileReplicationWorker.class);
        });
    }
    @Test void externalGcWiresOnlyItsOwnProductionStoreAndNeedsNoPeerStore() {
        var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var source=mock(javax.sql.DataSource.class);org.mockito.Mockito.when(jdbc.getDataSource()).thenReturn(source);
        var site=new SiteProperties();site.setMode(SiteProperties.Mode.EXTERNAL);site.setId("external");site.setInstanceId(java.util.UUID.randomUUID());
        var settings=new FileGcProperties();settings.setEnabled(true);settings.setInventoryEnabled(true);settings.setCoordinatedEnabled(true);
        settings.setDryRun(false);settings.setExecuteEnabled(true);settings.setExecuteAck("synthetic-drill");settings.setExecuteClasses(java.util.Set.of("UNREFERENCED_REVISION"));
        var protocol=new you.v50to.eatwhat.sync.file.FileGcProtocol(jdbc,
                new you.v50to.eatwhat.sync.transport.NodeIdentity(site.getId(),site.getInstanceId()),
                new you.v50to.eatwhat.sync.transport.NodeIdentity("campus",java.util.UUID.randomUUID()),site.getMode());
        configured(false).withUserConfiguration(FileGcService.class).withPropertyValues("app.storage.files.gc.enabled=true")
                .withBean(org.springframework.jdbc.core.JdbcTemplate.class,()->jdbc)
                .withBean(org.springframework.transaction.PlatformTransactionManager.class,()->new org.springframework.jdbc.datasource.DataSourceTransactionManager(source))
                .withBean(SiteProperties.class,()->site).withBean(FileGcProperties.class,()->settings)
                .withBean(you.v50to.eatwhat.sync.file.FileGcProtocol.class,()->protocol).run(c->{
                    assertThat(c).hasNotFailed();assertThat(c).hasSingleBean(FileGcService.class);
                    assertThat(c).doesNotHaveBean("peerReplicaStore");assertThat(c).doesNotHaveBean(FileReplicationWorker.class);
                    assertThat(c.getBean(FileGcService.class).currentMode()).isEqualTo(FileGcService.Mode.EXECUTE);
                    var supplier=(java.util.function.Supplier<?>)ReflectionTestUtils.getField(c.getBean(FileGcService.class),"localStore");
                    assertThat(supplier.get()).isSameAs(c.getBean("localReplicaStore"));
                });
    }
    @Test void campusCopyWorkerBindsEachProductionStoreByExplicitQualifier() {
        configured(true).run(c->{
            assertThat(c).hasNotFailed();assertThat(c).hasSingleBean(FileReplicationWorker.class);
            var worker=c.getBean(FileReplicationWorker.class);
            assertThat(ReflectionTestUtils.getField(worker,"localStore")).isSameAs(c.getBean("localReplicaStore"));
            assertThat(ReflectionTestUtils.getField(worker,"peerStore")).isSameAs(c.getBean("peerReplicaStore"));
        });
    }
}
