package you.v50to.eatwhat.service.storage.replication;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import you.v50to.eatwhat.config.FileReplicationProperties;
import you.v50to.eatwhat.config.S3StorageProperties;

/**
 * Configuration for replication object stores.
 * Conditionally enabled via app.storage.files.replication.enabled property.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.storage.files.replication.enabled", havingValue = "true")
public class ReplicationStorageConfiguration {

    /**
     * Local replica store: built from the primary S3StorageProperties.
     * Provides access to the primary object storage during replication.
     *
     * @param s3Properties primary storage configuration
     * @param replicationProperties multipart, part-size and throughput settings
     * @return replica store for local (primary) site
     */
    @Bean
    public S3ReplicaObjectStore localReplicaStore(S3StorageProperties s3Properties,
                                                  FileReplicationProperties replicationProperties) {
        log.info("Initializing local replica store from S3StorageProperties");
        return new S3ReplicaObjectStore(
                s3Properties.getEndpoint(),
                s3Properties.getRegion(),
                s3Properties.getBucket(),
                s3Properties.getAccessKey(),
                s3Properties.getSecretKey(),
                s3Properties.isPathStyle(),
                tuning(replicationProperties));
    }

    /**
     * Peer replica store: built from FileReplicationProperties.peer.
     * Provides access to the peer (R2/CDN) object storage during replication.
     *
     * @param replicationProperties replication configuration including peer settings
     * @return replica store for peer site
     */
    @Bean
    @ConditionalOnProperty(name="app.storage.files.replication.worker-enabled",havingValue="true")
    public S3ReplicaObjectStore peerReplicaStore(FileReplicationProperties replicationProperties) {
        FileReplicationProperties.PeerConfig peer = replicationProperties.getPeer();
        log.info("Initializing peer replica store from FileReplicationProperties.peer");
        return new S3ReplicaObjectStore(
                peer.getEndpoint(),
                peer.getRegion(),
                peer.getBucket(),
                peer.getAccessKey(),
                peer.getSecretKey(),
                peer.isPathStyle(),
                tuning(replicationProperties));
    }

    /**
     * Multipart threshold, part size and the optional byte-rate limit. Every store gets its own limiter, so a copy
     * (download from one store, upload to the other) is limited per direction.
     */
    static S3ReplicaObjectStore.Tuning tuning(FileReplicationProperties properties) {
        ByteRateLimiter limiter = properties.getMaxBytesPerSecond() > 0
                ? new ByteRateLimiter(properties.getMaxBytesPerSecond())
                : ByteRateLimiter.unlimited();
        return new S3ReplicaObjectStore.Tuning(properties.getMultipartThreshold(), properties.getPartSize(), limiter);
    }
}
