package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import you.v50to.eatwhat.config.FileReplicationProperties;
import you.v50to.eatwhat.service.storage.replication.FilePublicationService;
import you.v50to.eatwhat.service.storage.replication.FileReplicationWorker;
import you.v50to.eatwhat.service.storage.replication.FileTransferJobStore;
import you.v50to.eatwhat.service.storage.replication.ReplicationConsentGate;
import you.v50to.eatwhat.service.storage.replication.S3ReplicaObjectStore;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for FileReplicationWorker crash recovery.
 * Tests that the worker correctly recovers from crashes at various points in the replication pipeline.
 */
class FileReplicationCrashIT {
    private static final String REGION = "us-east-1";
    private static final String CONTENT_TYPE = "application/octet-stream";

    private static GenericContainer<?> postgres;
    private static GenericContainer<?> rustfs;
    private static String localBucket;
    private static String peerBucket;
    private static String endpoint;
    private static String postgresUrlBase;
    private static S3ReplicaObjectStore localStore;
    private static S3ReplicaObjectStore peerStore;

    @BeforeAll
    static void start() throws Exception {
        // Start Postgres
        postgres = IsolatedContainers.postgres("frc-test");
        postgres.start();
        postgresUrlBase = IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE);

        // Start RustFS
        rustfs = IsolatedContainers.rustfs("frc-test");
        rustfs.start();
        endpoint = "http://" + rustfs.getHost() + ":" + rustfs.getMappedPort(9000);

        // Create buckets
        localBucket = "local-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        peerBucket = "peer-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        AwsBasicCredentials credentials = AwsBasicCredentials.create(
                IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY);

        try (S3Client admin = S3Client.builder().endpointOverride(URI.create(endpoint))
                .region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {
            admin.createBucket(CreateBucketRequest.builder().bucket(localBucket).build());
            admin.createBucket(CreateBucketRequest.builder().bucket(peerBucket).build());
        }

        // Create stores
        localStore = new S3ReplicaObjectStore(endpoint, REGION, localBucket,
                IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY, true);
        peerStore = new S3ReplicaObjectStore(endpoint, REGION, peerBucket,
                IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY, true);
    }

    @BeforeEach
    void freshDatabase() {
        String database = "it_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(postgresUrlBase,
                IsolatedContainers.USER, IsolatedContainers.PASSWORD))
                .execute("CREATE DATABASE " + database + " TEMPLATE template0");
        String url = IsolatedContainers.jdbcUrl(postgres, database);
        Flyway.configure().dataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true).load().migrate();
    }

    @AfterAll
    static void stop() {
        if (localStore != null) localStore.close();
        if (peerStore != null) peerStore.close();
        if (rustfs != null) rustfs.stop();
        if (postgres != null) postgres.stop();
    }

    @Test
    void crashAfterPutRecoveryCompleteWithoutRePut() throws Exception {
        // Setup: file in local bucket, enqueue COPY_TO_PEER job
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        String finalKey = "verified/" + fileId.toString().replace("-", "");
        byte[] content = testContent("after-put-crash");
        String sha256 = computeSha256(content);

        // Get fresh database URL for this test
        String database = "it_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(postgresUrlBase,
                IsolatedContainers.USER, IsolatedContainers.PASSWORD))
                .execute("CREATE DATABASE " + database + " TEMPLATE template0");
        String url = IsolatedContainers.jdbcUrl(postgres, database);
        Flyway.configure().dataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD));

        // Setup file metadata and upload to local store
        Path tmp = Files.createTempFile("test-", ".bin");
        try {
            Files.write(tmp, content);
            setupFileMetadata(jdbc, fileId, finalKey, sha256, content.length);
            localStore.putFile(finalKey, CONTENT_TYPE, tmp, sha256);
            jdbc.update("INSERT INTO eatwhat_file.file_transfer_job (job_id, file_id, revision, kind, target_site, status, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, NOW())",
                    UUID.randomUUID(), fileId, 1L, "COPY_TO_PEER", "PEER", "READY");
        } finally {
            Files.deleteIfExists(tmp);
        }

        // Launch child JVM with crash at "after-put" checkpoint
        Path signalFile = Files.createTempFile("crash-signal-", ".txt");
        Files.delete(signalFile); // the child creates it to signal the checkpoint
        try {
            Process child = launchReplicationCrashWorker("after-put", url, signalFile);

            // Wait for signal file (crash point)
            long deadline = System.currentTimeMillis() + 25000;
            while (!Files.exists(signalFile) && child.isAlive() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertTrue(Files.exists(signalFile), "Child should reach after-put checkpoint");
            assertTrue(child.isAlive(), "Child should still be alive (blocked)");

            // Kill child
            child.destroyForcibly();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Child should terminate after force-kill");
            assertNotEquals(0, child.exitValue(), "Child should exit with error");

            // Job should still be LEASED with stale lease
            String jobStatus = jdbc.queryForObject(
                    "SELECT status FROM eatwhat_file.file_transfer_job WHERE file_id=?",
                    String.class, fileId);
            assertEquals("LEASED", jobStatus, "Job should remain LEASED after crash");

            // Force lease into the past
            jdbc.update("UPDATE eatwhat_file.file_transfer_job SET lease_until=NOW()-INTERVAL '1 minute' WHERE file_id=?", fileId);

            // Run in-process worker recovery
            FilePublicationService publicationService = new FilePublicationService(jdbc);
            FileTransferJobStore jobStore = new FileTransferJobStore(jdbc, publicationService);
            FileReplicationProperties props = new FileReplicationProperties();
            props.setTempDir(Files.createTempDirectory("recovery-").toString());
            props.setRetryBase(Duration.ofMillis(50));
            props.setRetryMax(Duration.ofSeconds(2));
            props.setLeaseSeconds(120);
            props.setBatch(8);

            FileReplicationWorker worker = new FileReplicationWorker(jobStore, new AlwaysAllowConsentGate(), localStore, peerStore, props);
            worker.runOnce();

            // Verify: job is DONE, object exists on peer, no extra PUT calls needed
            String recoveredStatus = jdbc.queryForObject(
                    "SELECT status FROM eatwhat_file.file_transfer_job WHERE file_id=?",
                    String.class, fileId);
            assertEquals("DONE", recoveredStatus, "Job should be DONE after recovery");
            assertTrue(peerStore.head(finalKey).isPresent(), "Object should exist on peer");

            // Verify replica state
            String replicaState = jdbc.queryForObject(
                    "SELECT state FROM eatwhat_file.file_replica WHERE file_id=? AND site='PEER'",
                    String.class, fileId);
            assertEquals("PEER_VERIFIED", replicaState, "Peer replica should be PEER_VERIFIED");
        } finally {
            Files.deleteIfExists(signalFile);
        }
    }

    @Test
    void crashMidDownloadRecoveryRetransfers() throws Exception {
        // Setup: file in local bucket, enqueue COPY_TO_PEER job
        UUID fileId = UUID.randomUUID();
        String finalKey = "verified/" + fileId.toString().replace("-", "");
        byte[] content = testContent("mid-download-crash");
        String sha256 = computeSha256(content);

        // Get fresh database URL for this test
        String database = "it_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(postgresUrlBase,
                IsolatedContainers.USER, IsolatedContainers.PASSWORD))
                .execute("CREATE DATABASE " + database + " TEMPLATE template0");
        String url = IsolatedContainers.jdbcUrl(postgres, database);
        Flyway.configure().dataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD));

        // Setup file metadata and upload to local store
        Path tmp = Files.createTempFile("test-", ".bin");
        try {
            Files.write(tmp, content);
            setupFileMetadata(jdbc, fileId, finalKey, sha256, content.length);
            localStore.putFile(finalKey, CONTENT_TYPE, tmp, sha256);
            jdbc.update("INSERT INTO eatwhat_file.file_transfer_job (job_id, file_id, revision, kind, target_site, status, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, NOW())",
                    UUID.randomUUID(), fileId, 1L, "COPY_TO_PEER", "PEER", "READY");
        } finally {
            Files.deleteIfExists(tmp);
        }

        // Verify peer doesn't have the object initially
        assertFalse(peerStore.head(finalKey).isPresent(), "Peer should not have object initially");

        // Launch child JVM with crash at "mid-download" checkpoint
        Path signalFile = Files.createTempFile("crash-signal-", ".txt");
        Files.delete(signalFile); // the child creates it to signal the checkpoint
        try {
            Process child = launchReplicationCrashWorker("mid-download", url, signalFile);

            // Wait for signal file
            long deadline = System.currentTimeMillis() + 25000;
            while (!Files.exists(signalFile) && child.isAlive() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertTrue(Files.exists(signalFile), "Child should reach mid-download checkpoint");

            // Kill child
            child.destroyForcibly();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));

            // Force lease into the past
            jdbc.update("UPDATE eatwhat_file.file_transfer_job SET lease_until=NOW()-INTERVAL '1 minute' WHERE file_id=?", fileId);

            // Run recovery
            FilePublicationService publicationService = new FilePublicationService(jdbc);
            FileTransferJobStore jobStore = new FileTransferJobStore(jdbc, publicationService);
            FileReplicationProperties props = new FileReplicationProperties();
            props.setTempDir(Files.createTempDirectory("recovery-").toString());
            props.setRetryBase(Duration.ofMillis(50));
            props.setRetryMax(Duration.ofSeconds(2));
            props.setLeaseSeconds(120);
            props.setBatch(8);

            FileReplicationWorker worker = new FileReplicationWorker(jobStore, new AlwaysAllowConsentGate(), localStore, peerStore, props);
            worker.runOnce();

            // Verify: job is DONE, object exists on peer with correct SHA
            String recoveredStatus = jdbc.queryForObject(
                    "SELECT status FROM eatwhat_file.file_transfer_job WHERE file_id=?",
                    String.class, fileId);
            assertEquals("DONE", recoveredStatus, "Job should be DONE after recovery");
            assertTrue(peerStore.head(finalKey).isPresent(), "Object should exist on peer after recovery");

            var peerDigest = peerStore.readbackDigest(finalKey, content.length);
            assertEquals(sha256, peerDigest.sha256Hex(), "Peer object should have correct SHA");
        } finally {
            Files.deleteIfExists(signalFile);
        }
    }

    private Process launchReplicationCrashWorker(String checkpoint, String jdbcUrl, Path signalFile) throws Exception {
        String classpath = System.getProperty("java.class.path");
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xms32m", "-Xmx192m",
                "-Dlogging.config=classpath:logback-test.xml",
                "-cp", classpath,
                ReplicationCrashWorker.class.getName(),
                checkpoint, jdbcUrl, IsolatedContainers.USER, endpoint, localBucket, peerBucket, signalFile.toString()
        );
        builder.redirectErrorStream(true);
        Path logFile = Files.createTempFile("crash-worker-", ".log");
        builder.redirectOutput(logFile.toFile());
        builder.environment().clear();
        builder.environment().put("LANG", "C.UTF-8");
        builder.environment().put("TZ", "UTC");
        return builder.start();
    }

    private void setupFileMetadata(JdbcTemplate jdbc, UUID fileId, String finalKey, String sha256, long size) {
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key) VALUES(?, ?, 'test', ?)",
                fileId, UUID.randomUUID(), "test/" + fileId);
        jdbc.update("""
                INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at)
                VALUES(?, ?, ?, ?, ?, 'application/octet-stream', 'VERIFIED', clock_timestamp())
                """, fileId, (int) 1, finalKey, sha256, size);
        jdbc.update("""
                INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at)
                VALUES(?, ?, 'LOCAL', ?, 'AVAILABLE_LOCAL', ?, ?, clock_timestamp())
                """, fileId, (int) 1, finalKey, sha256, size);
        jdbc.update("""
                INSERT INTO eatwhat_file.file_publication(file_id, revision, global_state, created_at, updated_at)
                VALUES(?, ?, 'LOCAL_ONLY', clock_timestamp(), clock_timestamp())
                ON CONFLICT DO NOTHING
                """, fileId, (int) 1);
    }

    private static byte[] testContent(String seed) {
        byte[] content = new byte[4096];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) ((seed.hashCode() + i) % 256);
        }
        return content;
    }

    private static String computeSha256(byte[] content) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(content);
        return HexFormat.of().formatHex(md.digest());
    }

    private static class AlwaysAllowConsentGate implements ReplicationConsentGate {
        @Override
        public boolean replicationAllowed(UUID ownerGid) {
            return true;
        }

        @Override
        public Long currentEpoch(UUID ownerGid) {
            return 1L;
        }
    }
}
