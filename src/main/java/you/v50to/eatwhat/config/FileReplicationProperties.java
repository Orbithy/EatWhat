package you.v50to.eatwhat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuration for file replication between LOCAL (primary storage) and PEER (R2/CDN) sites.
 * Bidirectional sync is controlled by feature flags; all flags default to false until tested in production.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.storage.files.replication")
public class FileReplicationProperties {
    /** Enable the file replication service and database schema. Defaults to false. */
    private boolean enabled = false;

    /** Enable background worker threads for replication jobs. Defaults to false. */
    private boolean workerEnabled = false;

    /** Enable CDN output of replicated files. Defaults to false. */
    private boolean cdnOutputEnabled = false;

    /** Replication lease duration: workers must update status within this window. Defaults to 120 seconds. */
    private long leaseSeconds = 120;

    /** Batch size for pulling replication jobs from the queue. Defaults to 8. */
    private int batch = 8;

    /** Initial retry delay for failed transfers. Defaults to 5 seconds. */
    private Duration retryBase = Duration.ofSeconds(5);

    /** Maximum retry delay with exponential backoff. Defaults to 30 minutes. */
    private Duration retryMax = Duration.ofMinutes(30);

    /** Number of attempts after which to alert without quarantining. Defaults to 50. */
    private int alertAfterAttempts = 50;

    /** Poll interval for replication job status checks. Defaults to 5 seconds. */
    private Duration pollInterval = Duration.ofSeconds(5);

    /** Jitter factor for backoff (0.0-1.0). Defaults to 0.2. */
    private double jitter = 0.2;

    /** Temporary directory for file downloads during replication. Defaults to system temp directory. */
    private String tempDir = System.getProperty("java.io.tmpdir");

    /**
     * Objects of at least this many bytes are copied with S3 multipart upload instead of one PUT. Bytes; defaults to
     * 16 MiB. Must be >= part-size.
     */
    private long multipartThreshold = 16L * 1024 * 1024;

    /**
     * Size of every multipart part except the last. Bytes; defaults to 8 MiB. S3 requires at least 5 MiB for all
     * but the last part and at most 10000 parts, so app.storage.s3.max-file-size-bytes / part-size must stay at or
     * below 10000 (validated at startup). Allowed range 5 MiB..1 GiB. Peak memory of a copy does not depend on this
     * value (parts stream from the temp file); only the time one part needs against the lease heartbeat does.
     */
    private long partSize = 8L * 1024 * 1024;

    /**
     * Free space that must remain on the temp-dir file system after the object itself is downloaded (checked before the
     * download: usable space >= size + reserve). Below that the job is retried with code DISK_SPACE, never
     * quarantined. Bytes; defaults to 64 MiB, 0 disables the reserve.
     */
    private long tempReserveBytes = 64L * 1024 * 1024;

    /**
     * Throughput cap for downloads and uploads of each object store, bytes per second. 0 (default) means unlimited.
     */
    private long maxBytesPerSecond = 0;

    /** Verification mode: FULL_READBACK downloads and re-verifies SHA-256. */
    private VerifyMode verifyMode = VerifyMode.FULL_READBACK;

    /** Peer site (S3-compatible) endpoint and authentication. */
    @NestedConfigurationProperty
    private PeerConfig peer = new PeerConfig();

    @Data
    public static class PeerConfig {
        /** S3-compatible endpoint URL (e.g., https://s3.us-west-1.wasabisys.com for Wasabi). */
        private String endpoint;

        /** AWS region or region-equivalent for the S3-compatible service. */
        private String region;

        /** Bucket name for replicated files. */
        private String bucket;

        /** Access key for authentication. */
        private String accessKey;

        /** Secret key for authentication. */
        private String secretKey;

        /** Use path-style URLs (true) vs virtual-hosted-style (false) for S3. */
        private boolean pathStyle = false;

        /** CDN base URL for serving replicated files (e.g., https://cdn.example.com). */
        private String cdnBaseUrl;
    }

    public enum VerifyMode {
        /** Download file and re-compute SHA-256 before marking as verified. */
        FULL_READBACK
    }
}
