package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for FileTransferJobStore using a real PostgreSQL database.
 * Tests job claiming, fencing token management, lease expiry, and state transitions.
 */
class FileTransferJobStoreIT {

    @Test
    void claimReturnsJobAndBumpsFencingTokenAndAttempts() {
        try (var postgres = IsolatedContainers.postgres("file-transfer-job-store")) {
            postgres.start();
            String database = "ftj_" + UUID.randomUUID().toString().replace("-", "");
            JdbcTemplate server = jdbc(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE));
            server.execute("CREATE DATABASE " + database + " TEMPLATE template0");
            String url = IsolatedContainers.jdbcUrl(postgres, database);
            JdbcTemplate admin = jdbc(url);

            migrateToV28(url);

            // Seed data: file_object, file_revision (VERIFIED), file_transfer_job (READY)
            UUID fileId = UUID.randomUUID();
            UUID ownerGid = UUID.randomUUID();
            String sha256 = "a".repeat(64);
            long sizeBytes = 1000L;
            String contentType = "image/png";
            String finalKey = "verified/" + fileId;

            admin.update(
                    "INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key, canonical_revision, created_at) " +
                    "VALUES(?, ?, ?, ?, 1, now())",
                    fileId, ownerGid, "test", "logical/" + fileId);
            admin.update(
                    "INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at, created_at) " +
                    "VALUES(?, 1, ?, ?, ?, ?, 'VERIFIED', now(), now())",
                    fileId, finalKey, sha256, sizeBytes, contentType);
            admin.update(
                    "INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, updated_at) " +
                    "VALUES(?, 1, 'LOCAL', ?, 'AVAILABLE_LOCAL', now())",
                    fileId, finalKey);

            UUID jobId = UUID.randomUUID();
            admin.update(
                    "INSERT INTO eatwhat_file.file_transfer_job(job_id, file_id, revision, kind, target_site, status, attempts, next_attempt_at, created_at, updated_at) " +
                    "VALUES(?, ?, 1, 'COPY_TO_PEER', 'PEER', 'READY', 0, now(), now(), now())",
                    jobId, fileId);

            // Claim the job
            var store = new you.v50to.eatwhat.service.storage.replication.FileTransferJobStore(admin, new you.v50to.eatwhat.service.storage.replication.FilePublicationService(admin));
            List<you.v50to.eatwhat.service.storage.replication.FileTransferJobStore.Job> jobs = store.claim(10, Duration.ofSeconds(120));

            assertEquals(1, jobs.size());
            var job = jobs.getFirst();
            assertEquals(jobId, job.jobId());
            assertEquals(fileId, job.fileId());
            assertEquals(1, job.revision());
            assertEquals("COPY_TO_PEER", job.kind());
            assertEquals("PEER", job.targetSite());
            assertEquals(1, job.attempts()); // incremented from 0
            assertEquals(1L, job.fencingToken()); // incremented from 0
            assertEquals(sha256, job.sha256());
            assertEquals(sizeBytes, job.sizeBytes());
            assertEquals(contentType, job.contentType());
            assertEquals(finalKey, job.finalKey());
            assertEquals(ownerGid, job.ownerGid());

            // Verify job in DB is now LEASED with non-null lease_until
            Map<String, Object> dbJob = admin.queryForMap(
                    "SELECT status, attempts, fencing_token, lease_until FROM eatwhat_file.file_transfer_job WHERE job_id=?",
                    jobId);
            assertEquals("LEASED", dbJob.get("status"));
            assertEquals(1, dbJob.get("attempts"));
            assertEquals(1L, dbJob.get("fencing_token"));
            assertNotNull(dbJob.get("lease_until"));
        }
    }

    @Test
    void staleTokenPreventsMutations() {
        try (var postgres = IsolatedContainers.postgres("file-transfer-job-stale")) {
            postgres.start();
            String database = "ftj_stale_" + UUID.randomUUID().toString().replace("-", "");
            JdbcTemplate server = jdbc(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE));
            server.execute("CREATE DATABASE " + database + " TEMPLATE template0");
            String url = IsolatedContainers.jdbcUrl(postgres, database);
            JdbcTemplate admin = jdbc(url);

            migrateToV28(url);

            UUID fileId = UUID.randomUUID();
            UUID ownerGid = UUID.randomUUID();
            UUID jobId = UUID.randomUUID();
            seedFileAndJob(admin, fileId, ownerGid, jobId);

            var store = new you.v50to.eatwhat.service.storage.replication.FileTransferJobStore(admin, new you.v50to.eatwhat.service.storage.replication.FilePublicationService(admin));
            List<you.v50to.eatwhat.service.storage.replication.FileTransferJobStore.Job> jobs = store.claim(10, Duration.ofSeconds(120));
            assertEquals(1, jobs.size());
            var holder1 = jobs.getFirst();
            long token1 = holder1.fencingToken();

            // Force the lease_until into the past via SQL to simulate expiry
            admin.update("UPDATE eatwhat_file.file_transfer_job SET lease_until=clock_timestamp()-interval '1 second' WHERE job_id=?", jobId);

            // Claim again; fencing_token increments
            jobs = store.claim(10, Duration.ofSeconds(120));
            assertEquals(1, jobs.size());
            var holder2 = jobs.getFirst();
            long token2 = holder2.fencingToken();
            assertEquals(token1 + 1, token2);

            // Original holder's mutations should all fail with stale token
            assertFalse(store.complete(holder1, new you.v50to.eatwhat.service.storage.replication.ReplicaObjectStore.DigestResult(1000L, "b".repeat(64))),
                    "complete should return false with stale token");
            assertFalse(store.retry(holder1, Duration.ofSeconds(5), "TEST_ERROR", null),
                    "retry should return false with stale token");
            assertFalse(store.quarantine(holder1, "TEST_CODE", "test message"),
                    "quarantine should return false with stale token");
            assertFalse(store.pauseConsent(holder1),
                    "pauseConsent should return false with stale token");
            assertFalse(store.extendLease(holder1, Duration.ofSeconds(120)),
                    "extendLease should return false with stale token");

            // Verify job is still LEASED and unchanged
            Map<String, Object> dbJob = admin.queryForMap(
                    "SELECT status, attempts, fencing_token FROM eatwhat_file.file_transfer_job WHERE job_id=?",
                    jobId);
            assertEquals("LEASED", dbJob.get("status"));
            assertEquals(2, dbJob.get("attempts")); // only from the second claim
            assertEquals(token2, dbJob.get("fencing_token"));
        }
    }

    @Test
    void expiredLeaseCanBeReclaimedByNewWorker() {
        try (var postgres = IsolatedContainers.postgres("file-transfer-job-expired")) {
            postgres.start();
            String database = "ftj_expired_" + UUID.randomUUID().toString().replace("-", "");
            JdbcTemplate server = jdbc(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE));
            server.execute("CREATE DATABASE " + database + " TEMPLATE template0");
            String url = IsolatedContainers.jdbcUrl(postgres, database);
            JdbcTemplate admin = jdbc(url);

            migrateToV28(url);

            UUID fileId = UUID.randomUUID();
            UUID ownerGid = UUID.randomUUID();
            UUID jobId = UUID.randomUUID();
            seedFileAndJob(admin, fileId, ownerGid, jobId);

            var store = new you.v50to.eatwhat.service.storage.replication.FileTransferJobStore(admin, new you.v50to.eatwhat.service.storage.replication.FilePublicationService(admin));
            List<you.v50to.eatwhat.service.storage.replication.FileTransferJobStore.Job> jobs = store.claim(10, Duration.ofSeconds(120));
            assertEquals(1, jobs.size());
            var job1 = jobs.getFirst();

            // Force lease to expire
            admin.update("UPDATE eatwhat_file.file_transfer_job SET lease_until=clock_timestamp()-interval '1 second' WHERE job_id=?", jobId);

            // Reclaim should succeed with new token
            jobs = store.claim(10, Duration.ofSeconds(120));
            assertEquals(1, jobs.size());
            var job2 = jobs.getFirst();
            assertTrue(job2.fencingToken() > job1.fencingToken());
            assertEquals("LEASED", admin.queryForObject(
                    "SELECT status FROM eatwhat_file.file_transfer_job WHERE job_id=?", String.class, jobId));
        }
    }

    @Test
    void doubleClaimYieldsDisjointSetsEachJobOnce() throws InterruptedException {
        try (var postgres = IsolatedContainers.postgres("file-transfer-job-concurrent")) {
            postgres.start();
            String database = "ftj_concurrent_" + UUID.randomUUID().toString().replace("-", "");
            JdbcTemplate server = jdbc(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE));
            server.execute("CREATE DATABASE " + database + " TEMPLATE template0");
            String url = IsolatedContainers.jdbcUrl(postgres, database);
            JdbcTemplate admin = jdbc(url);

            migrateToV28(url);

            // Seed 20 jobs
            UUID ownerGid = UUID.randomUUID();
            for (int i = 0; i < 20; i++) {
                UUID fileId = UUID.randomUUID();
                seedFileAndJob(admin, fileId, ownerGid, UUID.randomUUID());
            }

            var store = new you.v50to.eatwhat.service.storage.replication.FileTransferJobStore(admin, new you.v50to.eatwhat.service.storage.replication.FilePublicationService(admin));
            Set<UUID> claimed1 = new HashSet<>();
            Set<UUID> claimed2 = new HashSet<>();
            CountDownLatch latch = new CountDownLatch(2);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            executor.submit(() -> {
                List<you.v50to.eatwhat.service.storage.replication.FileTransferJobStore.Job> jobs = store.claim(10, Duration.ofSeconds(120));
                jobs.forEach(j -> claimed1.add(j.jobId()));
                latch.countDown();
            });
            executor.submit(() -> {
                List<you.v50to.eatwhat.service.storage.replication.FileTransferJobStore.Job> jobs = store.claim(10, Duration.ofSeconds(120));
                jobs.forEach(j -> claimed2.add(j.jobId()));
                latch.countDown();
            });

            assertTrue(latch.await(10, TimeUnit.SECONDS));
            executor.shutdown();

            // Verify disjoint
            Set<UUID> intersection = new HashSet<>(claimed1);
            intersection.retainAll(claimed2);
            assertTrue(intersection.isEmpty(), "No job should be claimed twice");

            // Verify coverage
            Set<UUID> union = new HashSet<>(claimed1);
            union.addAll(claimed2);
            assertEquals(20, union.size(), "All 20 jobs should be claimed exactly once");
        }
    }

    @Test
    void completeForPeerTargetSetsReplicaStateAndJobDone() {
        try (var postgres = IsolatedContainers.postgres("file-transfer-job-complete")) {
            postgres.start();
            String database = "ftj_complete_" + UUID.randomUUID().toString().replace("-", "");
            JdbcTemplate server = jdbc(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE));
            server.execute("CREATE DATABASE " + database + " TEMPLATE template0");
            String url = IsolatedContainers.jdbcUrl(postgres, database);
            JdbcTemplate admin = jdbc(url);

            migrateToV28(url);

            UUID fileId = UUID.randomUUID();
            UUID ownerGid = UUID.randomUUID();
            UUID jobId = UUID.randomUUID();
            String finalKey = "verified/" + fileId;
            seedFileAndJob(admin, fileId, ownerGid, jobId, "COPY_TO_PEER", "PEER", finalKey);

            var store = new you.v50to.eatwhat.service.storage.replication.FileTransferJobStore(admin, new you.v50to.eatwhat.service.storage.replication.FilePublicationService(admin));
            List<you.v50to.eatwhat.service.storage.replication.FileTransferJobStore.Job> jobs = store.claim(10, Duration.ofSeconds(120));
            var job = jobs.getFirst();

            String verifiedSha = "b".repeat(64);
            long verifiedSize = 2000L;
            boolean result = store.complete(job, new you.v50to.eatwhat.service.storage.replication.ReplicaObjectStore.DigestResult(verifiedSize, verifiedSha));
            assertTrue(result);

            // Job should be DONE
            String status = admin.queryForObject(
                    "SELECT status FROM eatwhat_file.file_transfer_job WHERE job_id=?", String.class, jobId);
            assertEquals("DONE", status);

            // Replica should be PEER_VERIFIED with verified sha/size
            Map<String, Object> replica = admin.queryForMap(
                    "SELECT state, verified_sha256, verified_size FROM eatwhat_file.file_replica WHERE file_id=? AND revision=1 AND site='PEER'",
                    fileId);
            assertEquals("PEER_VERIFIED", replica.get("state"));
            assertEquals(verifiedSha, replica.get("verified_sha256"));
            assertEquals(verifiedSize, replica.get("verified_size"));
        }
    }

    @Test
    void quarantineWritesAlertAndSetStatus() {
        try (var postgres = IsolatedContainers.postgres("file-transfer-job-quarantine")) {
            postgres.start();
            String database = "ftj_quarantine_" + UUID.randomUUID().toString().replace("-", "");
            JdbcTemplate server = jdbc(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE));
            server.execute("CREATE DATABASE " + database + " TEMPLATE template0");
            String url = IsolatedContainers.jdbcUrl(postgres, database);
            JdbcTemplate admin = jdbc(url);

            migrateToV28(url);

            UUID fileId = UUID.randomUUID();
            UUID ownerGid = UUID.randomUUID();
            UUID jobId = UUID.randomUUID();
            seedFileAndJob(admin, fileId, ownerGid, jobId);

            var store = new you.v50to.eatwhat.service.storage.replication.FileTransferJobStore(admin, new you.v50to.eatwhat.service.storage.replication.FilePublicationService(admin));
            List<you.v50to.eatwhat.service.storage.replication.FileTransferJobStore.Job> jobs = store.claim(10, Duration.ofSeconds(120));
            var job = jobs.getFirst();

            boolean result = store.quarantine(job, "SHA_MISMATCH", "Expected sha=abc, got xyz");
            assertTrue(result);

            // Job should be QUARANTINED
            String status = admin.queryForObject(
                    "SELECT status FROM eatwhat_file.file_transfer_job WHERE job_id=?", String.class, jobId);
            assertEquals("QUARANTINED", status);

            // Exactly one alert should exist
            int alertCount = admin.queryForObject(
                    "SELECT count(*) FROM eatwhat_file.file_replica_alert WHERE file_id=? AND revision=1",
                    Integer.class, fileId);
            assertEquals(1, alertCount);

            Map<String, Object> alert = admin.queryForMap(
                    "SELECT alert_type, message, site FROM eatwhat_file.file_replica_alert WHERE file_id=? AND revision=1",
                    fileId);
            assertEquals("SHA_MISMATCH", alert.get("alert_type"));
            assertEquals("Expected sha=abc, got xyz", alert.get("message"));
            assertEquals("PEER", alert.get("site"));
        }
    }

    @Test
    void retryResetsStatusAndSchedulesFutureAttempt() {
        try (var postgres = IsolatedContainers.postgres("file-transfer-job-retry")) {
            postgres.start();
            String database = "ftj_retry_" + UUID.randomUUID().toString().replace("-", "");
            JdbcTemplate server = jdbc(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE));
            server.execute("CREATE DATABASE " + database + " TEMPLATE template0");
            String url = IsolatedContainers.jdbcUrl(postgres, database);
            JdbcTemplate admin = jdbc(url);

            migrateToV28(url);

            UUID fileId = UUID.randomUUID();
            UUID ownerGid = UUID.randomUUID();
            UUID jobId = UUID.randomUUID();
            seedFileAndJob(admin, fileId, ownerGid, jobId);

            var store = new you.v50to.eatwhat.service.storage.replication.FileTransferJobStore(admin, new you.v50to.eatwhat.service.storage.replication.FilePublicationService(admin));
            List<you.v50to.eatwhat.service.storage.replication.FileTransferJobStore.Job> jobs = store.claim(10, Duration.ofSeconds(120));
            var job = jobs.getFirst();

            boolean result = store.retry(job, Duration.ofSeconds(30), "TRANSIENT_ERROR", "Connection timeout");
            assertTrue(result);

            // Job should be READY with future next_attempt_at
            Map<String, Object> dbJob = admin.queryForMap(
                    "SELECT status, next_attempt_at, lease_until FROM eatwhat_file.file_transfer_job WHERE job_id=?",
                    jobId);
            assertEquals("READY", dbJob.get("status"));
            assertNull(dbJob.get("lease_until"));
            // next_attempt_at should be in the future (approximately 30 seconds from now)
            assertNotNull(dbJob.get("next_attempt_at"));

            // Immediate claim should return nothing (job not yet due)
            jobs = store.claim(10, Duration.ofSeconds(120));
            assertTrue(jobs.isEmpty(), "Job should not be claimable yet");
        }
    }

    @Test
    void pauseConsentMarksJobPaused() {
        try (var postgres = IsolatedContainers.postgres("file-transfer-job-pause")) {
            postgres.start();
            String database = "ftj_pause_" + UUID.randomUUID().toString().replace("-", "");
            JdbcTemplate server = jdbc(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE));
            server.execute("CREATE DATABASE " + database + " TEMPLATE template0");
            String url = IsolatedContainers.jdbcUrl(postgres, database);
            JdbcTemplate admin = jdbc(url);

            migrateToV28(url);

            UUID fileId = UUID.randomUUID();
            UUID ownerGid = UUID.randomUUID();
            UUID jobId = UUID.randomUUID();
            seedFileAndJob(admin, fileId, ownerGid, jobId);

            var store = new you.v50to.eatwhat.service.storage.replication.FileTransferJobStore(admin, new you.v50to.eatwhat.service.storage.replication.FilePublicationService(admin));
            List<you.v50to.eatwhat.service.storage.replication.FileTransferJobStore.Job> jobs = store.claim(10, Duration.ofSeconds(120));
            var job = jobs.getFirst();

            boolean result = store.pauseConsent(job);
            assertTrue(result);

            // Job should be PAUSED_CONSENT with no lease
            Map<String, Object> dbJob = admin.queryForMap(
                    "SELECT status, lease_until FROM eatwhat_file.file_transfer_job WHERE job_id=?",
                    jobId);
            assertEquals("PAUSED_CONSENT", dbJob.get("status"));
            assertNull(dbJob.get("lease_until"));
        }
    }

    private static void seedFileAndJob(JdbcTemplate admin, UUID fileId, UUID ownerGid, UUID jobId) {
        seedFileAndJob(admin, fileId, ownerGid, jobId, "COPY_TO_PEER", "PEER", "verified/" + fileId);
    }

    private static void seedFileAndJob(JdbcTemplate admin, UUID fileId, UUID ownerGid, UUID jobId,
                                       String kind, String targetSite, String finalKey) {
        admin.update(
                "INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key, canonical_revision, created_at) " +
                "VALUES(?, ?, ?, ?, 1, now())",
                fileId, ownerGid, "test", "logical/" + fileId);
        admin.update(
                "INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at, created_at) " +
                "VALUES(?, 1, ?, ?, ?, ?, 'VERIFIED', now(), now())",
                fileId, finalKey, "a".repeat(64), 1000L, "image/png");
        admin.update(
                "INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, updated_at) " +
                "VALUES(?, 1, 'LOCAL', ?, 'AVAILABLE_LOCAL', now())",
                fileId, finalKey);
        admin.update(
                "INSERT INTO eatwhat_file.file_transfer_job(job_id, file_id, revision, kind, target_site, status, attempts, next_attempt_at, created_at, updated_at) " +
                "VALUES(?, ?, 1, ?, ?, 'READY', 0, now(), now(), now())",
                jobId, fileId, kind, targetSite);
    }

    private static void migrateToV28(String url) {
        Flyway.configure()
                .dataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .load()
                .migrate();
    }

    private static JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(new DriverManagerDataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD));
    }
}
