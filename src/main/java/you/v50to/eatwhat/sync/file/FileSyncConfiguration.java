package you.v50to.eatwhat.sync.file;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.config.*;
import you.v50to.eatwhat.config.sync.SyncTransportProperties;
import you.v50to.eatwhat.service.storage.replication.*;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.NodeIdentity;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="app.sync.files.enabled",havingValue="true")
@EnableConfigurationProperties({FileSyncProperties.class,SyncTransportProperties.class})
public class FileSyncConfiguration {
    @Bean public FileEventPolicy fileEventPolicy(ConsentStore consent) { return new FileEventPolicy(consent); }
    @Bean public FileSyncWorker fileSyncWorker(JdbcTemplate jdbc,SyncTransactions tx,OutboxService outbox,ConsentStore consent,
            SiteProperties site,SyncTransportProperties transport,FileReplicationProperties replication,FileSyncProperties settings,FileReplicationEnqueuer enqueuer,FileGcProtocol gcProtocol) {
        settings.validate(site,transport,replication.isEnabled());
        var worker=new FileSyncWorker(jdbc,tx,outbox,consent,new NodeIdentity(site.getId(),site.getInstanceId()),peer(transport,site),settings.getBatchSize());
        worker.setEnqueuer(enqueuer);
        worker.setGcProtocol(gcProtocol);
        return worker;
    }
    @Bean public FileGcProtocol fileGcProtocol(JdbcTemplate jdbc,SiteProperties site,SyncTransportProperties transport) {
        return new FileGcProtocol(jdbc,new NodeIdentity(site.getId(),site.getInstanceId()),peer(transport,site),site.getMode());
    }
    @Bean public FileSyncRuntime fileSyncRuntime(FileSyncWorker worker,FileSyncProperties settings) { return new FileSyncRuntime(worker,settings); }
    @Bean public FileEventHandler fileMetadataHandler(JdbcTemplate jdbc,ConsentStore consent,FileReplicaCatalog catalog,
            FilePublicationService publication,SyncTransportProperties transport,S3StorageProperties storage,SiteProperties site) {
        return handler(FileEvents.METADATA,jdbc,consent,catalog,publication,transport,storage,site);
    }
    @Bean public FileEventHandler fileReferencesHandler(JdbcTemplate jdbc,ConsentStore consent,FileReplicaCatalog catalog,
            FilePublicationService publication,SyncTransportProperties transport,S3StorageProperties storage,SiteProperties site) {
        return handler(FileEvents.REFERENCES,jdbc,consent,catalog,publication,transport,storage,site);
    }
    @Bean public FileEventHandler fileReceiptHandler(JdbcTemplate jdbc,ConsentStore consent,FileReplicaCatalog catalog,
            FilePublicationService publication,SyncTransportProperties transport,S3StorageProperties storage,SiteProperties site) {
        return handler(FileEvents.RECEIPT,jdbc,consent,catalog,publication,transport,storage,site);
    }
    @Bean public FileEventHandler fileReplicaHandler(JdbcTemplate jdbc,ConsentStore consent,FileReplicaCatalog catalog,
            FilePublicationService publication,SyncTransportProperties transport,S3StorageProperties storage,SiteProperties site) {
        return handler(FileEvents.REPLICA,jdbc,consent,catalog,publication,transport,storage,site);
    }
    private static FileEventHandler handler(String type,JdbcTemplate jdbc,ConsentStore consent,FileReplicaCatalog catalog,
            FilePublicationService publication,SyncTransportProperties transport,S3StorageProperties storage,SiteProperties site) {
        return new FileEventHandler(type,jdbc,consent,catalog,publication,peer(transport,site),storage.getMaxFileSizeBytes(),site.getMode());
    }
    private static NodeIdentity peer(SyncTransportProperties t,SiteProperties site) { return you.v50to.eatwhat.cluster.ClusterRouting.configured(site)?null:new NodeIdentity(t.getPeerSiteId(),t.getPeerInstanceId()); }
}
