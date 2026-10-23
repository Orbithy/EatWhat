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
import you.v50to.eatwhat.service.storage.replication.FileReplicaCatalog;
import you.v50to.eatwhat.service.storage.replication.FileReplicationWorker;
import you.v50to.eatwhat.service.storage.replication.FileTransferJobStore;
import you.v50to.eatwhat.service.storage.replication.ObjectNotFoundException;
import you.v50to.eatwhat.service.storage.replication.ReplicationConsentGate;
import you.v50to.eatwhat.service.storage.replication.S3ReplicaObjectStore;

import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for FileReplicationWorker with RustFS and Postgres.
 * Tests the 7-step replication pipeline, error handling, and consent gating.
 */
class FileReplicationWorkerIT {
    private static final String REGION = "us-east-1";
    private static final String CONTENT_TYPE = "application/octet-stream";

    private static GenericContainer<?> postgres;
    private static GenericContainer<?> rustfs;
    private static JdbcTemplate jdbc;
    private static FileTransferJobStore jobStore;
    private static FileReplicationWorker worker;
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
        postgres = IsolatedContainers.postgres("frw-test");
        postgres.start();
        postgresUrlBase = IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE);

        // Start RustFS
        rustfs = IsolatedContainers.rustfs("frw-test");
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
        props.setTempDir(Files.createTempDirectory("frw-test-").toString());
        props.setRetryBase(Duration.ofMillis(100));
        props.setRetryMax(Duration.ofSeconds(5));
        props.setJitter(0.2);
        props.setAlertAfterAttempts(50);
        props.setLeaseSeconds(120);
        props.setBatch(8);

    }

    /** A fresh migrated database per test, so claim() never picks up another test's jobs. */
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
    void copyToPeerHappyPath() throws Exception {
        // Setup: file in local bucket, job ready
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String finalKey = "verified/" + fileId.toString().replace("-", "");

        byte[] content = testContent("happy path");
        String sha256 = computeSha256(content);
        Path srcFile = Files.createTempFile("test-", ".bin");
        try {
            Files.write(srcFile, content);

            // Insert file metadata
            setupFileMetadata(fileId, ownerGid, finalKey, sha256, content.length, 1);

            // Upload to local store
            localStore.putFile(finalKey, CONTENT_TYPE, srcFile, sha256);

            // Enqueue and claim job
            jobStore.enqueue(fileId, 1, "COPY_TO_PEER", "PEER", null);
            var jobs = jobStore.claim(1, Duration.ofSeconds(120));
            assertTrue(!jobs.isEmpty(), "Should claim job");

            var job = jobs.get(0);
            FakeConsentGate consent = new FakeConsentGate(true, 1L);
            worker = new FileReplicationWorker(jobStore, consent, localStore, peerStore, props);

            // Process
            var outcome = worker.process(job);

            // Verify
            assertEquals(FileReplicationWorker.Outcome.COMPLETED, outcome);
            assertTrue(peerStore.head(finalKey).isPresent(), "Peer should have object");
            var peerDigest = peerStore.readbackDigest(finalKey, content.length);
            assertEquals(sha256, peerDigest.sha256Hex());

            // Check job status is DONE
            String jobStatus = jdbc.queryForObject(
                    "SELECT status FROM eatwhat_file.file_transfer_job WHERE job_id=?",
                    String.class, job.jobId());
            assertEquals("DONE", jobStatus);

            // Check PEER replica is PEER_VERIFIED with correct SHA
            String replicaState = jdbc.queryForObject(
                    "SELECT state FROM eatwhat_file.file_replica WHERE file_id=? AND revision=1 AND site='PEER'",
                    String.class, fileId);
            assertEquals("PEER_VERIFIED", replicaState);
            String replicaSha = jdbc.queryForObject(
                    "SELECT verified_sha256 FROM eatwhat_file.file_replica WHERE file_id=? AND revision=1 AND site='PEER'",
                    String.class, fileId);
            assertEquals(sha256, replicaSha);

            // Check publication state is PEER_PENDING (not AVAILABLE_GLOBAL yet)
            String pubState = jdbc.queryForObject(
                    "SELECT global_state FROM eatwhat_file.file_publication WHERE file_id=? AND revision=1",
                    String.class, fileId);
            assertEquals("PEER_PENDING", pubState, "Publication should be PEER_PENDING before peer confirmation");

            // Mark peer confirmed and verify AVAILABLE_GLOBAL
            FilePublicationService publicationService = new FilePublicationService(jdbc);
            publicationService.markPeerConfirmed(fileId, 1);
            String pubStateAfterConfirm = jdbc.queryForObject(
                    "SELECT global_state FROM eatwhat_file.file_publication WHERE file_id=? AND revision=1",
                    String.class, fileId);
            assertEquals("AVAILABLE_GLOBAL", pubStateAfterConfirm, "Publication should be AVAILABLE_GLOBAL after peer confirmation");
        } finally {
            Files.deleteIfExists(srcFile);
        }
    }

    @Test
    void pullFromPeerHappyPath() throws Exception {
        // Setup: file in peer bucket, use recordPeerRevision to seed job
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        String finalKey = "verified/" + fileId.toString().replace("-", "");

        byte[] content = testContent("pull path");
        String sha256 = computeSha256(content);
        Path srcFile = Files.createTempFile("test-", ".bin");
        try {
            Files.write(srcFile, content);

            // Use recordPeerRevision to seed the peer revision and job
            peerStore.putFile(finalKey, CONTENT_TYPE, srcFile, sha256);
            FileReplicaCatalog catalog = new FileReplicaCatalog(jdbc);
            FileReplicaCatalog.PeerRevision peer = new FileReplicaCatalog.PeerRevision(
                    fileId, 1, ownerGid, "test", "test/" + fileId, finalKey, sha256, content.length, CONTENT_TYPE);
            catalog.recordPeerRevision(peer);

            // Claim and process the job
            var jobs = jobStore.claim(1, Duration.ofSeconds(120));
            assertTrue(!jobs.isEmpty(), "Should have job from recordPeerRevision");

            var job = jobs.get(0);
            FakeConsentGate consent = new FakeConsentGate(true, 1L);
            worker = new FileReplicationWorker(jobStore, consent, localStore, peerStore, props);

            var outcome = worker.process(job);

            assertEquals(FileReplicationWorker.Outcome.COMPLETED, outcome);
            assertTrue(localStore.head(finalKey).isPresent(), "Local should have object");
            var localDigest = localStore.readbackDigest(finalKey, content.length);
            assertEquals(sha256, localDigest.sha256Hex());

            // Check job status is DONE
            String jobStatus = jdbc.queryForObject(
                    "SELECT status FROM eatwhat_file.file_transfer_job WHERE job_id=?",
                    String.class, job.jobId());
            assertEquals("DONE", jobStatus);

            // Check LOCAL replica is AVAILABLE_LOCAL with verified SHA
            String replicaState = jdbc.queryForObject(
                    "SELECT state FROM eatwhat_file.file_replica WHERE file_id=? AND revision=1 AND site='LOCAL'",
                    String.class, fileId);
            assertEquals("AVAILABLE_LOCAL", replicaState);
            String replicaSha = jdbc.queryForObject(
                    "SELECT verified_sha256 FROM eatwhat_file.file_replica WHERE file_id=? AND revision=1 AND site='LOCAL'",
                    String.class, fileId);
            assertEquals(sha256, replicaSha);
        } finally {
            Files.deleteIfExists(srcFile);
        }
    }

    @Test
    void pullIntoAPhysicallyDeletedLocalReplicaIsQuarantinedAsTargetDeletedAndNeverResurrectsIt() throws Exception {
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        String finalKey = "verified/" + fileId.toString().replace("-", "");
        byte[] content = testContent("target deleted");
        String sha256 = computeSha256(content);
        Path srcFile = Files.createTempFile("test-", ".bin");
        try {
            Files.write(srcFile, content);
            peerStore.putFile(finalKey, CONTENT_TYPE, srcFile, sha256);
            new FileReplicaCatalog(jdbc).recordPeerRevision(new FileReplicaCatalog.PeerRevision(
                    fileId, 1, ownerGid, "test", "test/" + fileId, finalKey, sha256, content.length, CONTENT_TYPE));
            jdbc.update("UPDATE eatwhat_file.file_replica SET state='DELETED' WHERE file_id=? AND site='LOCAL'", fileId);
            var job = jobStore.claim(1, Duration.ofSeconds(120)).get(0);
            worker = new FileReplicationWorker(jobStore, new FakeConsentGate(true, 1L), localStore, peerStore, props);

            var outcome = worker.process(job);

            assertEquals(FileReplicationWorker.Outcome.QUARANTINED, outcome);
            assertEquals("QUARANTINED", jdbc.queryForObject("SELECT status FROM eatwhat_file.file_transfer_job WHERE job_id=?", String.class, job.jobId()),
                    "the transfer is not recorded as done against a deleted replica");
            assertEquals("DELETED", jdbc.queryForObject("SELECT state FROM eatwhat_file.file_replica WHERE file_id=? AND site='LOCAL'", String.class, fileId),
                    "the replica is never resurrected");
            assertEquals("TARGET_DELETED", jdbc.queryForObject("SELECT alert_type FROM eatwhat_file.file_replica_alert WHERE file_id=?", String.class, fileId));
            assertTrue(localStore.head(finalKey).isEmpty(),"a deleted target must receive no physical PUT");
        } finally {
            Files.deleteIfExists(srcFile);
        }
    }

    @Test
    void sourceObjectMissingRetriesWithoutQuarantine() throws Exception {
        // Job ready but source object doesn't exist -> retry, then upload bytes and recover
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        String finalKey = "verified/" + fileId.toString().replace("-", "");

        byte[] content = testContent("missing src");
        String sha256 = computeSha256(content);
        Path srcFile = Files.createTempFile("test-", ".bin");

        try {
            Files.write(srcFile, content);
            setupFileMetadata(fileId, ownerGid, finalKey, sha256, content.length, 1);
            jobStore.enqueue(fileId, 1, "COPY_TO_PEER", "PEER", null);
            var jobs = jobStore.claim(1, Duration.ofSeconds(120));

            var job = jobs.get(0);
            FakeConsentGate consent = new FakeConsentGate(true, 1L);
            worker = new FileReplicationWorker(jobStore, consent, localStore, peerStore, props);

            // First process: source missing
            var outcome = worker.process(job);

            assertEquals(FileReplicationWorker.Outcome.RETRY, outcome);
            String status = jdbc.queryForObject("SELECT status FROM eatwhat_file.file_transfer_job WHERE job_id=?",
                    String.class, job.jobId());
            assertEquals("READY", status, "Job should be READY for retry");
            String lastError = jdbc.queryForObject("SELECT last_error FROM eatwhat_file.file_transfer_job WHERE job_id=?",
                    String.class, job.jobId());
            assertEquals("SOURCE_NOT_FOUND", lastError);

            // Now upload the bytes to source
            localStore.putFile(finalKey, CONTENT_TYPE, srcFile, sha256);

            // Make the job due for immediate re-processing
            jdbc.update("UPDATE eatwhat_file.file_transfer_job SET next_attempt_at=clock_timestamp() WHERE job_id=?",
                    job.jobId());

            // Claim and process again
            var jobs2 = jobStore.claim(1, Duration.ofSeconds(120));
            assertTrue(!jobs2.isEmpty(), "Should claim job again");
            var job2 = jobs2.get(0);
            worker = new FileReplicationWorker(jobStore, consent, localStore, peerStore, props);

            outcome = worker.process(job2);

            assertEquals(FileReplicationWorker.Outcome.COMPLETED, outcome, "Should complete after bytes available");
            String finalStatus = jdbc.queryForObject("SELECT status FROM eatwhat_file.file_transfer_job WHERE job_id=?",
                    String.class, job2.jobId());
            assertEquals("DONE", finalStatus);
            assertTrue(peerStore.head(finalKey).isPresent(), "Peer should now have object");
        } finally {
            Files.deleteIfExists(srcFile);
        }
    }

    @Test
    void pullFromADeletedPeerSourceIsQuarantinedBeforeAnyObjectCopy() throws Exception {
        UUID file=UUID.randomUUID();String key="verified/"+file;byte[] bytes=testContent("deleted peer source");String sha=computeSha256(bytes);
        Path body=Files.createTempFile("deleted-peer-",".bin");
        try {
            Files.write(body,bytes);peerStore.putFile(key,CONTENT_TYPE,body,sha);
            new FileReplicaCatalog(jdbc).recordPeerRevision(new FileReplicaCatalog.PeerRevision(file,1,UUID.randomUUID(),"test","test/"+file,key,sha,bytes.length,CONTENT_TYPE));
            jdbc.update("UPDATE eatwhat_file.file_replica SET state='DELETED' WHERE file_id=? AND site='PEER'",file);
            var job=jobStore.claim(1,Duration.ofSeconds(120)).get(0);
            worker=new FileReplicationWorker(jobStore,new FakeConsentGate(true,1L),localStore,peerStore,props);
            assertEquals(FileReplicationWorker.Outcome.QUARANTINED,worker.process(job));
            assertEquals("SOURCE_DELETED",jdbc.queryForObject("SELECT alert_type FROM eatwhat_file.file_replica_alert WHERE file_id=?",String.class,file));
            assertTrue(localStore.head(key).isEmpty());
        } finally {Files.deleteIfExists(body);}
    }

    @Test
    void copyToPeerOfAPhysicallyDeletedLocalSourceIsQuarantinedWithoutRetry() throws Exception {
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        String finalKey = "verified/" + fileId.toString().replace("-", "");
        byte[] content = testContent("deleted source");
        String sha256 = computeSha256(content);

        setupFileMetadata(fileId, ownerGid, finalKey, sha256, content.length, 1);
        // The GC removed the local object and marked the replica; the object is NOT in the local bucket.
        jobStore.enqueue(fileId, 1, "COPY_TO_PEER", "PEER", null);
        jdbc.update("UPDATE eatwhat_file.file_replica SET state='DELETED' WHERE file_id=? AND site='LOCAL'", fileId);
        assertThrows(org.springframework.dao.DataAccessException.class,()->jobStore.enqueue(fileId,1,"COPY_TO_PEER","PEER",null));
        var job = jobStore.claim(1, Duration.ofSeconds(120)).get(0);
        worker = new FileReplicationWorker(jobStore, new FakeConsentGate(true, 1L), localStore, peerStore, props);

        var outcome = worker.process(job);

        assertEquals(FileReplicationWorker.Outcome.QUARANTINED, outcome, "no retry loop for a deleted source");
        assertEquals("QUARANTINED", jdbc.queryForObject("SELECT status FROM eatwhat_file.file_transfer_job WHERE job_id=?",
                String.class, job.jobId()));
        assertEquals("SOURCE_DELETED", jdbc.queryForObject(
                "SELECT alert_type FROM eatwhat_file.file_replica_alert WHERE file_id=? AND revision=1", String.class, fileId));
        assertTrue(peerStore.head(finalKey).isEmpty(), "nothing was copied");
    }

    @Test
    void destinationPreExistingWrongObjectQuarantined() throws Exception {
        // Destination has wrong object; should quarantine, never overwrite
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        String finalKey = "verified/" + fileId.toString().replace("-", "");

        byte[] sourceContent = testContent("source");
        String sourceSha = computeSha256(sourceContent);
        Path srcFile = Files.createTempFile("test-", ".bin");

        byte[] wrongContent = testContent("wrong");
        String wrongSha = computeSha256(wrongContent);
        Path wrongFile = Files.createTempFile("wrong-", ".bin");

        try {
            Files.write(srcFile, sourceContent);
            Files.write(wrongFile, wrongContent);

            setupFileMetadata(fileId, ownerGid, finalKey, sourceSha, sourceContent.length, 1);
            localStore.putFile(finalKey, CONTENT_TYPE, srcFile, sourceSha);
            peerStore.putFile(finalKey, CONTENT_TYPE, wrongFile, wrongSha);

            jobStore.enqueue(fileId, 1, "COPY_TO_PEER", "PEER", null);
            var jobs = jobStore.claim(1, Duration.ofSeconds(120));

            var job = jobs.get(0);
            FakeConsentGate consent = new FakeConsentGate(true, 1L);
            worker = new FileReplicationWorker(jobStore, consent, localStore, peerStore, props);

            var outcome = worker.process(job);

            assertEquals(FileReplicationWorker.Outcome.QUARANTINED, outcome);
            // Verify destination was not overwritten
            var peerDigest = peerStore.readbackDigest(finalKey, wrongContent.length);
            assertEquals(wrongSha, peerDigest.sha256Hex(), "Destination should remain unchanged");

            String status = jdbc.queryForObject("SELECT status FROM eatwhat_file.file_transfer_job WHERE job_id=?",
                    String.class, job.jobId());
            assertEquals("QUARANTINED", status);

            // Verify exactly one alert row with TARGET_MISMATCH
            Long alertCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM eatwhat_file.file_replica_alert WHERE file_id=? AND revision=1",
                    Long.class, fileId);
            assertEquals(1, alertCount, "Should have exactly one alert row");
            String alertType = jdbc.queryForObject(
                    "SELECT alert_type FROM eatwhat_file.file_replica_alert WHERE file_id=? AND revision=1",
                    String.class, fileId);
            assertEquals("TARGET_MISMATCH", alertType);
        } finally {
            Files.deleteIfExists(srcFile);
            Files.deleteIfExists(wrongFile);
        }
    }

    @Test
    void lostAckCompleteWithoutPut() throws Exception {
        // Destination already has correct object, but job is READY
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        String finalKey = "verified/" + fileId.toString().replace("-", "");

        byte[] content = testContent("lost ack");
        String sha256 = computeSha256(content);
        Path srcFile = Files.createTempFile("test-", ".bin");

        try {
            Files.write(srcFile, content);

            setupFileMetadata(fileId, ownerGid, finalKey, sha256, content.length, 1);
            localStore.putFile(finalKey, CONTENT_TYPE, srcFile, sha256);

            // Pre-populate peer with correct object (simulating lost ACK)
            peerStore.putFile(finalKey, CONTENT_TYPE, srcFile, sha256);

            jobStore.enqueue(fileId, 1, "COPY_TO_PEER", "PEER", null);
            var jobs = jobStore.claim(1, Duration.ofSeconds(120));

            // Wrap peer store to count puts
            CountingPeerStore countingPeer = new CountingPeerStore(peerStore);
            var job = jobs.get(0);
            FakeConsentGate consent = new FakeConsentGate(true, 1L);
            worker = new FileReplicationWorker(jobStore, consent, localStore, countingPeer, props);

            var outcome = worker.process(job);

            assertEquals(FileReplicationWorker.Outcome.COMPLETED, outcome);
            assertEquals(0, countingPeer.putCount, "Should not call putFile for existing correct object");

            String status = jdbc.queryForObject("SELECT status FROM eatwhat_file.file_transfer_job WHERE job_id=?",
                    String.class, job.jobId());
            assertEquals("DONE", status);
        } finally {
            Files.deleteIfExists(srcFile);
        }
    }

    @Test
    void consentRevokedBeforeProcessingPauses() throws Exception {
        // Consent withdrawn before processing starts
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        String finalKey = "verified/" + fileId.toString().replace("-", "");

        byte[] content = testContent("consent revoked");
        String sha256 = computeSha256(content);
        Path srcFile = Files.createTempFile("test-", ".bin");

        try {
            Files.write(srcFile, content);
            setupFileMetadata(fileId, ownerGid, finalKey, sha256, content.length, 1);
            localStore.putFile(finalKey, CONTENT_TYPE, srcFile, sha256);

            jobStore.enqueue(fileId, 1, "COPY_TO_PEER", "PEER", null);
            var jobs = jobStore.claim(1, Duration.ofSeconds(120));

            var job = jobs.get(0);
            FakeConsentGate consent = new FakeConsentGate(false, 1L); // Consent denied
            worker = new FileReplicationWorker(jobStore, consent, localStore, peerStore, props);

            var outcome = worker.process(job);

            assertEquals(FileReplicationWorker.Outcome.PAUSED, outcome);
            String status = jdbc.queryForObject("SELECT status FROM eatwhat_file.file_transfer_job WHERE job_id=?",
                    String.class, job.jobId());
            assertEquals("PAUSED_CONSENT", status);
        } finally {
            Files.deleteIfExists(srcFile);
        }
    }

    @Test
    void shaHashMismatchQuarantined() throws Exception {
        // Source object size matches but SHA differs
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        String finalKey = "verified/" + fileId.toString().replace("-", "");

        byte[] actualContent = testContent("actual content");
        byte[] expectedContent = testContent("expected");  // Different content, may differ in size
        String actualSha = computeSha256(actualContent);
        String expectedSha = computeSha256(expectedContent);
        long expectedSize = expectedContent.length;

        Path srcFile = Files.createTempFile("test-", ".bin");

        try {
            Files.write(srcFile, actualContent);

            // Register with wrong SHA but actual size from actualContent
            setupFileMetadata(fileId, ownerGid, finalKey, expectedSha, expectedSize, 1);
            localStore.putFile(finalKey, CONTENT_TYPE, srcFile, actualSha);

            jobStore.enqueue(fileId, 1, "COPY_TO_PEER", "PEER", null);
            var jobs = jobStore.claim(1, Duration.ofSeconds(120));

            var job = jobs.get(0);
            FakeConsentGate consent = new FakeConsentGate(true, 1L);
            worker = new FileReplicationWorker(jobStore, consent, localStore, peerStore, props);

            var outcome = worker.process(job);

            assertEquals(FileReplicationWorker.Outcome.QUARANTINED, outcome);
            String status = jdbc.queryForObject("SELECT status FROM eatwhat_file.file_transfer_job WHERE job_id=?",
                    String.class, job.jobId());
            assertEquals("QUARANTINED", status);

            // Verify peer has no object (never created)
            assertTrue(peerStore.head(finalKey).isEmpty(), "Peer should not have object");

            // Verify exactly one alert row with SOURCE_MISMATCH
            Long alertCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM eatwhat_file.file_replica_alert WHERE file_id=? AND revision=1",
                    Long.class, fileId);
            assertEquals(1, alertCount, "Should have exactly one alert row");
            String alertType = jdbc.queryForObject(
                    "SELECT alert_type FROM eatwhat_file.file_replica_alert WHERE file_id=? AND revision=1",
                    String.class, fileId);
            assertEquals("SOURCE_MISMATCH", alertType);
        } finally {
            Files.deleteIfExists(srcFile);
        }
    }

    @Test
    void networkErrorDuringDownloadRetries() throws Exception {
        // Source store throws IO error mid-transfer
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        String finalKey = "verified/" + fileId.toString().replace("-", "");

        byte[] content = testContent("network error");
        String sha256 = computeSha256(content);
        Path srcFile = Files.createTempFile("test-", ".bin");

        try {
            Files.write(srcFile, content);
            setupFileMetadata(fileId, ownerGid, finalKey, sha256, content.length, 1);
            localStore.putFile(finalKey, CONTENT_TYPE, srcFile, sha256);

            jobStore.enqueue(fileId, 1, "COPY_TO_PEER", "PEER", null);
            var jobs = jobStore.claim(1, Duration.ofSeconds(120));

            // Wrap to throw error after some reads
            FaultySrcStore faultySrc = new FaultySrcStore(localStore);
            var job = jobs.get(0);
            FakeConsentGate consent = new FakeConsentGate(true, 1L);
            worker = new FileReplicationWorker(jobStore, consent, faultySrc, peerStore, props);

            var outcome = worker.process(job);

            assertEquals(FileReplicationWorker.Outcome.RETRY, outcome);
            String status = jdbc.queryForObject("SELECT status FROM eatwhat_file.file_transfer_job WHERE job_id=?",
                    String.class, job.jobId());
            assertEquals("READY", status, "Should be READY for retry");
            // Verify peer doesn't have the object
            assertTrue(peerStore.head(finalKey).isEmpty(), "Peer should not have incomplete object");

            // Assert no leftover temp files in temp directory
            Path tempDir = java.nio.file.Paths.get(props.getTempDir());
            try (var stream = Files.list(tempDir)) {
                long tempFiles = stream.count();
                assertEquals(0, tempFiles, "No leftover temp files should remain");
            }

            // Now remove the fault and process again (the source object is still in place)
            jdbc.update("UPDATE eatwhat_file.file_transfer_job SET next_attempt_at=clock_timestamp() WHERE job_id=?",
                    job.jobId());

            var jobs2 = jobStore.claim(1, Duration.ofSeconds(120));
            assertTrue(!jobs2.isEmpty(), "Should claim job again");
            var job2 = jobs2.get(0);
            worker = new FileReplicationWorker(jobStore, consent, localStore, peerStore, props);

            outcome = worker.process(job2);

            assertEquals(FileReplicationWorker.Outcome.COMPLETED, outcome, "Should complete with healthy source");
            assertTrue(peerStore.head(finalKey).isPresent(), "Peer should now have object");
            var peerDigest = peerStore.readbackDigest(finalKey, content.length);
            assertEquals(sha256, peerDigest.sha256Hex(), "Peer bytes should match source");
        } finally {
            Files.deleteIfExists(srcFile);
        }
    }

    @Test
    void staleLeaseDuringPutReturnsLostLease() throws Exception {
        // Lease expires while blocked in putFile
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        String finalKey = "verified/" + fileId.toString().replace("-", "");

        byte[] content = testContent("stale lease");
        String sha256 = computeSha256(content);
        Path srcFile = Files.createTempFile("test-", ".bin");

        try {
            Files.write(srcFile, content);
            setupFileMetadata(fileId, ownerGid, finalKey, sha256, content.length, 1);
            localStore.putFile(finalKey, CONTENT_TYPE, srcFile, sha256);

            jobStore.enqueue(fileId, 1, "COPY_TO_PEER", "PEER", null);
            var jobs = jobStore.claim(1, Duration.ofSeconds(120));

            var job = jobs.get(0);
            long originalFencingToken = job.fencingToken();

            // Wrap src store to force lease expiry before extendLease call
            LeaseExpiringSrcStore leaseExpiringSrc = new LeaseExpiringSrcStore(localStore, jdbc, job.jobId());
            FakeConsentGate consent = new FakeConsentGate(true, 1L);
            worker = new FileReplicationWorker(jobStore, consent, leaseExpiringSrc, peerStore, props);

            var outcome = worker.process(job);

            assertEquals(FileReplicationWorker.Outcome.LOST_LEASE, outcome);

            // Verify job row is unchanged by the first holder
            String jobStatus = jdbc.queryForObject("SELECT status FROM eatwhat_file.file_transfer_job WHERE job_id=?",
                    String.class, job.jobId());
            assertEquals("LEASED", jobStatus, "Job should still be LEASED (lost holder's update)");

            // Verify no PEER_VERIFIED replica was created
            Long peerReplicaCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM eatwhat_file.file_replica WHERE file_id=? AND revision=1 AND site='PEER' AND state='PEER_VERIFIED'",
                    Long.class, fileId);
            assertEquals(0, peerReplicaCount, "PEER_VERIFIED replica should not exist");

            // Verify peer doesn't have the object
            assertTrue(peerStore.head(finalKey).isEmpty(), "Peer should not have object from failed holder");
        } finally {
            Files.deleteIfExists(srcFile);
        }
    }

    /**
     * Fake consent gate for testing with settable consent state.
     */
    static class FakeConsentGate implements ReplicationConsentGate {
        private final boolean allowed;
        private final Long epoch;

        FakeConsentGate(boolean allowed, Long epoch) {
            this.allowed = allowed;
            this.epoch = epoch;
        }

        @Override
        public boolean replicationAllowed(UUID ownerGid) {
            return allowed;
        }

        @Override
        public Long currentEpoch(UUID ownerGid) {
            return epoch;
        }
    }

    /**
     * Wrapping store that counts putFile calls.
     */
    static class CountingPeerStore implements you.v50to.eatwhat.service.storage.replication.ReplicaObjectStore {
        private final S3ReplicaObjectStore delegate;
        int putCount = 0;

        CountingPeerStore(S3ReplicaObjectStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public java.util.Optional<ObjectMeta> head(String key) {
            return delegate.head(key);
        }

        @Override
        public DigestResult downloadTo(String key, Path tmp, long maxBytes) {
            return delegate.downloadTo(key, tmp, maxBytes);
        }

        @Override
        public void putFile(String key, String contentType, Path src, String sha256Hex) {
            putCount++;
            delegate.putFile(key, contentType, src, sha256Hex);
        }

        @Override
        public DigestResult readbackDigest(String key, long maxBytes) {
            return delegate.readbackDigest(key, maxBytes);
        }
    }

    /**
     * Wrapping store that throws error mid-download.
     */
    static class FaultySrcStore implements you.v50to.eatwhat.service.storage.replication.ReplicaObjectStore {
        private final S3ReplicaObjectStore delegate;

        FaultySrcStore(S3ReplicaObjectStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public java.util.Optional<ObjectMeta> head(String key) {
            return delegate.head(key);
        }

        @Override
        public DigestResult downloadTo(String key, Path tmp, long maxBytes) {
            // Throw error to simulate network fault
            throw new UncheckedIOException(new java.io.IOException("Simulated network fault"));
        }

        @Override
        public void putFile(String key, String contentType, Path src, String sha256Hex) {
            delegate.putFile(key, contentType, src, sha256Hex);
        }

        @Override
        public DigestResult readbackDigest(String key, long maxBytes) {
            return delegate.readbackDigest(key, maxBytes);
        }
    }

    /**
     * Wrapping store that makes lease expire before extendLease is called.
     */
    static class LeaseExpiringSrcStore implements you.v50to.eatwhat.service.storage.replication.ReplicaObjectStore {
        private final S3ReplicaObjectStore delegate;
        private final JdbcTemplate jdbc;
        private final UUID jobId;
        private boolean alreadyExpired = false;

        LeaseExpiringSrcStore(S3ReplicaObjectStore delegate, JdbcTemplate jdbc, UUID jobId) {
            this.delegate = delegate;
            this.jdbc = jdbc;
            this.jobId = jobId;
        }

        @Override
        public java.util.Optional<ObjectMeta> head(String key) {
            return delegate.head(key);
        }

        @Override
        public DigestResult downloadTo(String key, Path tmp, long maxBytes) {
            if (!alreadyExpired) {
                // Expire the lease by setting lease_until to past
                jdbc.update(
                        "UPDATE eatwhat_file.file_transfer_job SET lease_until=clock_timestamp()-interval '1 minute' WHERE job_id=?",
                        jobId);
                alreadyExpired = true;
            }
            return delegate.downloadTo(key, tmp, maxBytes);
        }

        @Override
        public void putFile(String key, String contentType, Path src, String sha256Hex) {
            delegate.putFile(key, contentType, src, sha256Hex);
        }

        @Override
        public DigestResult readbackDigest(String key, long maxBytes) {
            return delegate.readbackDigest(key, maxBytes);
        }
    }

    @Test
    void sizeMismatchQuarantined() throws Exception {
        // Source object larger than revision size_bytes -> QUARANTINED
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        String finalKey = "verified/" + fileId.toString().replace("-", "");

        byte[] smallContent = testContent("small");
        byte[] largeContent = testContent("much larger content to exceed the size limit that was registered");
        String smallSha = computeSha256(smallContent);
        String largeSha = computeSha256(largeContent);

        Path smallFile = Files.createTempFile("test-", ".bin");
        Path largeFile = Files.createTempFile("large-", ".bin");

        try {
            Files.write(smallFile, smallContent);
            Files.write(largeFile, largeContent);

            // Register with small size but upload large content
            setupFileMetadata(fileId, ownerGid, finalKey, smallSha, smallContent.length, 1);
            localStore.putFile(finalKey, CONTENT_TYPE, largeFile, largeSha);

            jobStore.enqueue(fileId, 1, "COPY_TO_PEER", "PEER", null);
            var jobs = jobStore.claim(1, Duration.ofSeconds(120));

            var job = jobs.get(0);
            FakeConsentGate consent = new FakeConsentGate(true, 1L);
            worker = new FileReplicationWorker(jobStore, consent, localStore, peerStore, props);

            var outcome = worker.process(job);

            assertEquals(FileReplicationWorker.Outcome.QUARANTINED, outcome);
            String status = jdbc.queryForObject("SELECT status FROM eatwhat_file.file_transfer_job WHERE job_id=?",
                    String.class, job.jobId());
            assertEquals("QUARANTINED", status);

            // Verify peer has no object
            assertTrue(peerStore.head(finalKey).isEmpty(), "Peer should not have object");

            // Verify exactly one alert row with SOURCE_MISMATCH
            Long alertCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM eatwhat_file.file_replica_alert WHERE file_id=? AND revision=1",
                    Long.class, fileId);
            assertEquals(1, alertCount, "Should have exactly one alert row");
        } finally {
            Files.deleteIfExists(smallFile);
            Files.deleteIfExists(largeFile);
        }
    }

    @Test
    void retryExhaustedAlertsOnce() throws Exception {
        // Setup: transient IO error on every download, props.alertAfterAttempts=1
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        String finalKey = "verified/" + fileId.toString().replace("-", "");

        byte[] content = testContent("alert test");
        String sha256 = computeSha256(content);

        // Temporarily set alertAfterAttempts to 1 for this test
        int originalAlert = props.getAlertAfterAttempts();
        props.setAlertAfterAttempts(1);

        try {
            setupFileMetadata(fileId, ownerGid, finalKey, sha256, content.length, 1);
            jobStore.enqueue(fileId, 1, "COPY_TO_PEER", "PEER", null);
            var jobs = jobStore.claim(1, Duration.ofSeconds(120));

            var job = jobs.get(0);
            FakeConsentGate consent = new FakeConsentGate(true, 1L);
            // Use FaultySrcStore that always throws IOException
            FaultySrcStore faultySrc = new FaultySrcStore(localStore);
            worker = new FileReplicationWorker(jobStore, consent, faultySrc, peerStore, props);

            var outcome = worker.process(job);

            assertEquals(FileReplicationWorker.Outcome.RETRY, outcome, "Should retry, not quarantine");
            String status = jdbc.queryForObject("SELECT status FROM eatwhat_file.file_transfer_job WHERE job_id=?",
                    String.class, job.jobId());
            assertEquals("READY", status, "Job should be READY for retry");

            // Verify exactly one RETRY_EXHAUSTED alert row
            Long alertCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM eatwhat_file.file_replica_alert WHERE file_id=? AND revision=1 AND alert_type='RETRY_EXHAUSTED'",
                    Long.class, fileId);
            assertEquals(1, alertCount, "Should have exactly one RETRY_EXHAUSTED alert");
        } finally {
            // Restore original alertAfterAttempts
            props.setAlertAfterAttempts(originalAlert);
        }
    }

    // Helper methods

    private void setupFileMetadata(UUID fileId, UUID ownerGid, String finalKey, String sha256, long sizeBytes, int revision) {
        // Insert file_object
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key) VALUES(?, ?, 'test', ?)",
                fileId, ownerGid, "test/" + fileId.toString());

        // Insert file_revision as VERIFIED
        jdbc.update("""
                INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at)
                VALUES(?, ?, ?, ?, ?, ?, 'VERIFIED', clock_timestamp())
                """, fileId, revision, finalKey, sha256, sizeBytes, CONTENT_TYPE);

        // Insert LOCAL replica as AVAILABLE_LOCAL
        jdbc.update("""
                INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at)
                VALUES(?, ?, 'LOCAL', ?, 'AVAILABLE_LOCAL', ?, ?, clock_timestamp())
                """, fileId, revision, finalKey, sha256, sizeBytes);

        // Create initial publication row
        jdbc.update("""
                INSERT INTO eatwhat_file.file_publication(file_id, revision, global_state, created_at, updated_at)
                VALUES(?, ?, 'LOCAL_ONLY', clock_timestamp(), clock_timestamp())
                ON CONFLICT DO NOTHING
                """, fileId, revision);
    }

    private byte[] testContent(String label) {
        return ("test-" + label + "-" + System.nanoTime()).getBytes();
    }

    private String computeSha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(data));
    }
}
