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
import you.v50to.eatwhat.service.storage.replication.FileReplicationRunner;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for FileReplicationRunner scheduling and lifecycle.
 * Tests that the runner properly starts/stops the worker, processes jobs on schedule,
 * drains full batches immediately, and handles exceptions without killing the scheduler.
 */
class FileReplicationRunnerIT {
    private static final String REGION = "us-east-1";
    private static final String CONTENT_TYPE = "application/octet-stream";

    private static GenericContainer<?> postgres;
    private static GenericContainer<?> rustfs;
    private static JdbcTemplate jdbc;
    private static FileTransferJobStore jobStore;
    private static FileReplicationProperties props;
    private static S3ReplicaObjectStore localStore;
    private static S3ReplicaObjectStore peerStore;
    private static String localBucket;
    private static String peerBucket;
    private static String endpoint;
    private static String postgresUrlBase;

    @BeforeAll
    static void start() throws Exception {
        // Start Postgres
        postgres = IsolatedContainers.postgres("frr-test");
        postgres.start();
        postgresUrlBase = IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE);

        // Start RustFS
        rustfs = IsolatedContainers.rustfs("frr-test");
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

        // Create stores and config
        localStore = new S3ReplicaObjectStore(endpoint, REGION, localBucket,
                IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY, true);
        peerStore = new S3ReplicaObjectStore(endpoint, REGION, peerBucket,
                IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY, true);

        props = new FileReplicationProperties();
        props.setTempDir(Files.createTempDirectory("frr-test-").toString());
        props.setRetryBase(Duration.ofMillis(100));
        props.setRetryMax(Duration.ofSeconds(5));
        props.setJitter(0.2);
        props.setAlertAfterAttempts(50);
        props.setLeaseSeconds(120);
        props.setBatch(3);  // Use 3 for this test (will enqueue 3 jobs to test batch draining).
        props.setPollInterval(Duration.ofMillis(200));  // Short poll interval for tests.
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
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD));
        jobStore = new FileTransferJobStore(jdbc, new FilePublicationService(jdbc));
    }

    @AfterAll
    static void stop() {
        if (localStore != null) localStore.close();
        if (peerStore != null) peerStore.close();
        if (rustfs != null) rustfs.stop();
        if (postgres != null) postgres.stop();
    }

    @Test
    void runnerStartsAndStopsGracefully() throws Exception {
        FileReplicationWorker worker = new FileReplicationWorker(jobStore, new AlwaysAllowConsentGate(),
                localStore, peerStore, props);
        FileReplicationRunner runner = new FileReplicationRunner(worker, props);

        assertFalse(runner.isRunning(), "Should not be running initially");
        runner.start();
        assertTrue(runner.isRunning(), "Should be running after start");
        runner.stop();
        assertFalse(runner.isRunning(), "Should not be running after stop");
    }

    @Test
    void runnerProcessesJobsOnSchedule() throws Exception {
        // Setup 3 files and enqueue 3 jobs
        UUID fileId1 = UUID.randomUUID();
        UUID fileId2 = UUID.randomUUID();
        UUID fileId3 = UUID.randomUUID();
        String finalKey1 = "verified/" + fileId1.toString().replace("-", "");
        String finalKey2 = "verified/" + fileId2.toString().replace("-", "");
        String finalKey3 = "verified/" + fileId3.toString().replace("-", "");

        byte[] content1 = testContent("job1");
        byte[] content2 = testContent("job2");
        byte[] content3 = testContent("job3");
        String sha1 = computeSha256(content1);
        String sha2 = computeSha256(content2);
        String sha3 = computeSha256(content3);

        // Upload files to local store
        Path tmp1 = Files.createTempFile("test-", ".bin");
        Path tmp2 = Files.createTempFile("test-", ".bin");
        Path tmp3 = Files.createTempFile("test-", ".bin");
        try {
            Files.write(tmp1, content1);
            Files.write(tmp2, content2);
            Files.write(tmp3, content3);

            setupFileMetadata(fileId1, 1, finalKey1, sha1, content1.length, 1);
            setupFileMetadata(fileId2, 1, finalKey2, sha2, content2.length, 1);
            setupFileMetadata(fileId3, 1, finalKey3, sha3, content3.length, 1);

            localStore.putFile(finalKey1, CONTENT_TYPE, tmp1, sha1);
            localStore.putFile(finalKey2, CONTENT_TYPE, tmp2, sha2);
            localStore.putFile(finalKey3, CONTENT_TYPE, tmp3, sha3);

            // Enqueue 3 jobs
            jobStore.enqueue(fileId1, 1, "COPY_TO_PEER", "PEER", null);
            jobStore.enqueue(fileId2, 1, "COPY_TO_PEER", "PEER", null);
            jobStore.enqueue(fileId3, 1, "COPY_TO_PEER", "PEER", null);
        } finally {
            Files.deleteIfExists(tmp1);
            Files.deleteIfExists(tmp2);
            Files.deleteIfExists(tmp3);
        }

        // Start runner
        FileReplicationWorker worker = new FileReplicationWorker(jobStore, new AlwaysAllowConsentGate(),
                localStore, peerStore, props);
        FileReplicationRunner runner = new FileReplicationRunner(worker, props);
        runner.start();

        try {
            // Wait for all 3 jobs to complete (bounded to ~20s)
            long deadline = System.currentTimeMillis() + 20000;
            boolean allDone = false;
            while (System.currentTimeMillis() < deadline && !allDone) {
                String status1 = queryJobStatus(fileId1, 1);
                String status2 = queryJobStatus(fileId2, 1);
                String status3 = queryJobStatus(fileId3, 1);
                if ("DONE".equals(status1) && "DONE".equals(status2) && "DONE".equals(status3)) {
                    allDone = true;
                }
                Thread.sleep(50);
            }
            assertTrue(allDone, "All 3 jobs should be DONE within 20 seconds");

            // Verify all 3 objects exist on peer and have correct SHA
            assertTrue(peerStore.head(finalKey1).isPresent(), "Peer should have object 1");
            assertTrue(peerStore.head(finalKey2).isPresent(), "Peer should have object 2");
            assertTrue(peerStore.head(finalKey3).isPresent(), "Peer should have object 3");
            assertEquals(sha1, peerStore.readbackDigest(finalKey1, content1.length).sha256Hex());
            assertEquals(sha2, peerStore.readbackDigest(finalKey2, content2.length).sha256Hex());
            assertEquals(sha3, peerStore.readbackDigest(finalKey3, content3.length).sha256Hex());
        } finally {
            runner.stop();
        }
    }

    @Test
    void runnerStopsProcessingAfterStop() throws Exception {
        // Setup file and enqueue job
        UUID fileId = UUID.randomUUID();
        String finalKey = "verified/" + fileId.toString().replace("-", "");
        byte[] content = testContent("stop-test");
        String sha = computeSha256(content);
        Path tmp = Files.createTempFile("test-", ".bin");
        try {
            Files.write(tmp, content);
            setupFileMetadata(fileId, 1, finalKey, sha, content.length, 1);
            localStore.putFile(finalKey, CONTENT_TYPE, tmp, sha);
            jobStore.enqueue(fileId, 1, "COPY_TO_PEER", "PEER", null);
        } finally {
            Files.deleteIfExists(tmp);
        }

        // Start runner and let it process the first job
        FileReplicationWorker worker = new FileReplicationWorker(jobStore, new AlwaysAllowConsentGate(),
                localStore, peerStore, props);
        FileReplicationRunner runner = new FileReplicationRunner(worker, props);
        runner.start();

        // Wait for first job to complete
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && !"DONE".equals(queryJobStatus(fileId, 1))) {
            Thread.sleep(50);
        }
        String status1 = queryJobStatus(fileId, 1);
        assertEquals("DONE", status1, "First job should be DONE");

        // Stop runner and enqueue a 4th job
        runner.stop();
        UUID fileId4 = UUID.randomUUID();
        String finalKey4 = "verified/" + fileId4.toString().replace("-", "");
        byte[] content4 = testContent("job4");
        String sha4 = computeSha256(content4);
        Path tmp4 = Files.createTempFile("test-", ".bin");
        try {
            Files.write(tmp4, content4);
            setupFileMetadata(fileId4, 1, finalKey4, sha4, content4.length, 1);
            localStore.putFile(finalKey4, CONTENT_TYPE, tmp4, sha4);
            jobStore.enqueue(fileId4, 1, "COPY_TO_PEER", "PEER", null);
        } finally {
            Files.deleteIfExists(tmp4);
        }

        // Wait 1.5 seconds; job should still be READY (not claimed/processed)
        Thread.sleep(1500);
        String status4 = queryJobStatus(fileId4, 1);
        assertEquals("READY", status4, "Job enqueued after stop should remain READY");
    }

    @Test
    void runnerDoesNotDieOnWorkerException() throws Exception {
        // Setup file and enqueue job
        UUID fileId = UUID.randomUUID();
        String finalKey = "verified/" + fileId.toString().replace("-", "");
        byte[] content = testContent("exception-test");
        String sha = computeSha256(content);
        Path tmp = Files.createTempFile("test-", ".bin");
        try {
            Files.write(tmp, content);
            setupFileMetadata(fileId, 1, finalKey, sha, content.length, 1);
            localStore.putFile(finalKey, CONTENT_TYPE, tmp, sha);
            jobStore.enqueue(fileId, 1, "COPY_TO_PEER", "PEER", null);
        } finally {
            Files.deleteIfExists(tmp);
        }

        // Create a worker that throws on first call, then works normally
        ThrowingConsentGate gate = new ThrowingConsentGate(1);
        FileReplicationWorker worker = new FileReplicationWorker(jobStore, gate, localStore, peerStore, props);
        FileReplicationRunner runner = new FileReplicationRunner(worker, props);
        runner.start();

        try {
            // Wait for exception to occur and then for recovery (enqueue a 2nd job after exception)
            Thread.sleep(500);  // Let first exception happen

            // Enqueue a 2nd job
            UUID fileId2 = UUID.randomUUID();
            String finalKey2 = "verified/" + fileId2.toString().replace("-", "");
            byte[] content2 = testContent("job2-after-exception");
            String sha2 = computeSha256(content2);
            Path tmp2 = Files.createTempFile("test-", ".bin");
            try {
                Files.write(tmp2, content2);
                setupFileMetadata(fileId2, 1, finalKey2, sha2, content2.length, 1);
                localStore.putFile(finalKey2, CONTENT_TYPE, tmp2, sha2);
                jobStore.enqueue(fileId2, 1, "COPY_TO_PEER", "PEER", null);
            } finally {
                Files.deleteIfExists(tmp2);
            }

            // Wait for 2nd job to complete (proves scheduler survived the exception)
            long deadline = System.currentTimeMillis() + 10000;
            boolean done = false;
            while (System.currentTimeMillis() < deadline && !done) {
                String status = queryJobStatus(fileId2, 1);
                if ("DONE".equals(status)) {
                    done = true;
                }
                Thread.sleep(50);
            }
            assertTrue(done, "2nd job should complete despite exception in 1st cycle");
            assertTrue(peerStore.head(finalKey2).isPresent(), "2nd job object should reach peer");
        } finally {
            runner.stop();
        }
    }

    private void setupFileMetadata(UUID fileId, long revision, String finalKey, String sha256, long size, int region) {
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key) VALUES(?, ?, 'test', ?)",
                fileId, UUID.randomUUID(), "test/" + fileId);
        jdbc.update("""
                INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at)
                VALUES(?, ?, ?, ?, ?, 'application/octet-stream', 'VERIFIED', clock_timestamp())
                """, fileId, (int) revision, finalKey, sha256, size);
        jdbc.update("""
                INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at)
                VALUES(?, ?, 'LOCAL', ?, 'AVAILABLE_LOCAL', ?, ?, clock_timestamp())
                """, fileId, (int) revision, finalKey, sha256, size);
        jdbc.update("""
                INSERT INTO eatwhat_file.file_publication(file_id, revision, global_state, created_at, updated_at)
                VALUES(?, ?, 'LOCAL_ONLY', clock_timestamp(), clock_timestamp())
                ON CONFLICT DO NOTHING
                """, fileId, (int) revision);
    }

    private String queryJobStatus(UUID fileId, long revision) {
        return jdbc.queryForObject(
            "SELECT status FROM eatwhat_file.file_transfer_job WHERE file_id=? AND revision=?",
            String.class, fileId, revision
        );
    }

    private static byte[] testContent(String seed) {
        byte[] content = new byte[1024];
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

    private static class ThrowingConsentGate implements ReplicationConsentGate {
        private int throwCount;

        ThrowingConsentGate(int throwCount) {
            this.throwCount = throwCount;
        }

        @Override
        public boolean replicationAllowed(UUID ownerGid) {
            if (throwCount > 0) {
                throwCount--;
                throw new RuntimeException("Simulated exception for testing");
            }
            return true;
        }

        @Override
        public Long currentEpoch(UUID ownerGid) {
            return 1L;
        }
    }
}
