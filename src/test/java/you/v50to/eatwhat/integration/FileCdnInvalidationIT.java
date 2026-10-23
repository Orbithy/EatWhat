package you.v50to.eatwhat.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import you.v50to.eatwhat.config.FileCdnInvalidationProperties;
import you.v50to.eatwhat.config.FileGcProperties;
import you.v50to.eatwhat.config.FileReplicationProperties;
import you.v50to.eatwhat.config.S3StorageProperties;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.service.storage.CdnPaths;
import you.v50to.eatwhat.service.storage.FileAliasResolver;
import you.v50to.eatwhat.service.storage.FileLifecycleService;
import you.v50to.eatwhat.service.storage.S3CompatibleStorageService;
import you.v50to.eatwhat.service.storage.replication.CdnInvalidationProvider;
import you.v50to.eatwhat.service.storage.replication.CdnInvalidationProvider.Accepted;
import you.v50to.eatwhat.service.storage.replication.CdnInvalidationProvider.Permanent;
import you.v50to.eatwhat.service.storage.replication.CdnInvalidationProvider.Reason;
import you.v50to.eatwhat.service.storage.replication.CdnInvalidationProvider.Retryable;
import you.v50to.eatwhat.service.storage.replication.FileCdnInvalidationStore;
import you.v50to.eatwhat.service.storage.replication.FileCdnInvalidationWorker;
import you.v50to.eatwhat.service.storage.replication.FileGcService;
import you.v50to.eatwhat.service.storage.replication.FileUrlResolver;
import you.v50to.eatwhat.service.storage.replication.HttpWebhookCdnProvider;
import you.v50to.eatwhat.service.storage.replication.InMemoryFakeCdnProvider;
import you.v50to.eatwhat.service.storage.replication.NoopCdnProvider;
import you.v50to.eatwhat.service.storage.replication.ReplicationConsentGate;
import you.v50to.eatwhat.service.storage.replication.S3ReplicaObjectStore;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** CDN invalidation queue: enqueue-with-release, worker states, webhook provider, T12 and the GC chain. */
class FileCdnInvalidationIT {
    private static final String REGION = "us-east-1";
    private static final String CONTENT_TYPE = "application/octet-stream";
    private static final String SHA = "a".repeat(64);
    private static final String TEMPLATE_DATABASE = "cdn_template";
    private static final String PUBLIC_BASE = "https://cdn.example.test/pub";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static GenericContainer<?> postgres;
    private static GenericContainer<?> rustfs;
    private static String postgresUrlBase;
    private static String endpoint;

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager tx;
    private S3ReplicaObjectStore realStore;
    private FileCdnInvalidationProperties props;
    private FileCdnInvalidationStore tasks;
    private InMemoryFakeCdnProvider fake;
    private FileCdnInvalidationWorker worker;

    @BeforeAll
    static void start() {
        postgres = IsolatedContainers.postgres("cdn-test");
        postgres.start();
        postgresUrlBase = IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE);
        rustfs = IsolatedContainers.rustfs("cdn-test");
        rustfs.start();
        endpoint = "http://" + rustfs.getHost() + ":" + rustfs.getMappedPort(9000);
        admin().execute("CREATE DATABASE " + TEMPLATE_DATABASE + " TEMPLATE template0");
        Flyway.configure().dataSource(IsolatedContainers.jdbcUrl(postgres, TEMPLATE_DATABASE),
                        IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true).load().migrate();
    }

    @AfterAll
    static void stop() {
        if (rustfs != null) rustfs.stop();
        if (postgres != null) postgres.stop();
    }

    private static JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(postgresUrlBase, IsolatedContainers.USER, IsolatedContainers.PASSWORD));
    }

    @BeforeEach
    void fresh() {
        String database = "it_" + uuid();
        admin().execute("CREATE DATABASE " + database + " TEMPLATE " + TEMPLATE_DATABASE);
        dataSource = new DriverManagerDataSource(IsolatedContainers.jdbcUrl(postgres, database), IsolatedContainers.USER, IsolatedContainers.PASSWORD);
        jdbc = new JdbcTemplate(dataSource);
        tx = new DataSourceTransactionManager(dataSource);

        String bucket = "cdn-" + uuid().substring(0, 16);
        try (S3Client s3 = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build()) {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
        realStore = new S3ReplicaObjectStore(endpoint, REGION, bucket,
                IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY, true);

        props = new FileCdnInvalidationProperties();
        props.setEnabled(true);
        props.setBatch(10);
        props.setRetryBase(Duration.ofSeconds(30));
        props.setRetryMax(Duration.ofMinutes(30));
        props.setJitter(0.0);
        props.setAlertAfterAttempts(2);
        tasks = new FileCdnInvalidationStore(jdbc);
        fake = new InMemoryFakeCdnProvider();
        worker = new FileCdnInvalidationWorker(tasks, fake, props, () -> PUBLIC_BASE, new Random(7));
    }

    @AfterEach
    void close() {
        realStore.close();
    }

    // =====================================================================================================
    // enqueue happens in the database, atomically with the release
    // =====================================================================================================

    @Test
    void releasingThePublicReferenceCreatesExactlyOneRevokeTaskAndItStoresNoUrl() throws Exception {
        Seed seed = seed("PUBLIC");

        release(seed, "r0");

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM eatwhat_file.file_cdn_invalidation WHERE file_id=?", seed.fileId());
        assertEquals(1, rows.size());
        assertEquals("REVOKE", rows.get(0).get("reason"));
        assertEquals("READY", rows.get(0).get("status"));
        assertEquals(1, rows.get(0).get("generation"));
        assertEquals(0, rows.get(0).get("attempts"));
        assertNull(rows.get(0).get("lease_until"));
        List<String> columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema='eatwhat_file' AND table_name='file_cdn_invalidation'", String.class);
        assertTrue(columns.stream().noneMatch(c -> c.contains("url") || c.contains("path")), "the queue stores neither urls nor paths: " + columns);
    }

    @Test
    void privateOnlyReleaseCreatesNoTask() throws Exception {
        Seed seed = seed("PRIVATE");

        release(seed, "r0");

        assertEquals(0, taskCount(seed));
    }

    @Test
    void releasingOneOfTwoPublicReferencesCreatesNoneUntilTheLastOneGoes() throws Exception {
        Seed seed = seed("PUBLIC", "PUBLIC");

        release(seed, "r0");
        assertEquals(0, taskCount(seed), "a live public reference remains");
        release(seed, "r1");
        assertEquals(1, taskCount(seed));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_cdn_invalidation WHERE file_id=? AND status='READY'", Integer.class, seed.fileId()));
    }

    @Test
    void aSecondReleaseCycleAfterARestoreCreatesGenerationTwo() throws Exception {
        Seed seed = seed("PUBLIC");
        release(seed, "r0");
        jdbc.update("UPDATE eatwhat_file.file_cdn_invalidation SET status='DONE', completed_at=clock_timestamp() WHERE file_id=?", seed.fileId());

        restore(seed, "r0");
        assertEquals(1, taskCount(seed), "a revive deletes nothing and creates nothing");
        release(seed, "r0");

        assertEquals(2, taskCount(seed));
        assertEquals(List.of(1, 2), jdbc.queryForList("SELECT generation FROM eatwhat_file.file_cdn_invalidation WHERE file_id=? AND reason='REVOKE' ORDER BY generation", Integer.class, seed.fileId()));
        assertEquals("READY", jdbc.queryForObject("SELECT status FROM eatwhat_file.file_cdn_invalidation WHERE file_id=? AND generation=2", String.class, seed.fileId()));
    }

    @Test
    void aStillReadyTaskIsNotDuplicatedByARestoreAndReleaseCycle() throws Exception {
        Seed seed = seed("PUBLIC");
        release(seed, "r0");
        restore(seed, "r0");
        release(seed, "r0");

        assertEquals(1, taskCount(seed), "a READY REVOKE task already covers the revision");
    }

    @Test
    void rollbackOfTheBusinessTransactionLeavesNoTask() throws Exception {
        Seed seed = seed("PUBLIC");
        FileLifecycleService lifecycle = new FileLifecycleService(jdbc, null, null, null, tx);

        new TransactionTemplate(tx).executeWithoutResult(status -> {
            assertEquals(1, lifecycle.releaseReferences("food", "r0", null, "ENTITY_DELETED"));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_cdn_invalidation WHERE file_id=?", Integer.class, seed.fileId()),
                    "visible inside the releasing transaction");
            status.setRollbackOnly();
        });

        assertEquals(0, taskCount(seed));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_reference_live WHERE logical_key=?", Integer.class, seed.logicalKey()));
    }

    @Test
    void taskTableIsRetainedAndItsIdentityIsImmutable() throws Exception {
        Seed seed = seed("PUBLIC");
        release(seed, "r0");

        assertThrows(DataAccessException.class, () -> jdbc.update("DELETE FROM eatwhat_file.file_cdn_invalidation"));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE eatwhat_file.file_cdn_invalidation SET generation=5"));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE eatwhat_file.file_cdn_invalidation SET reason='DELETE'"));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE eatwhat_file.file_cdn_invalidation SET status='LEASED'"), "LEASED needs a lease");
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE eatwhat_file.file_cdn_invalidation SET status='BOGUS'"));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE eatwhat_file.file_cdn_invalidation SET fencing_token=-1"));
        jdbc.update("UPDATE eatwhat_file.file_cdn_invalidation SET status='DONE', completed_at=clock_timestamp()");
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE eatwhat_file.file_cdn_invalidation SET status='READY'"), "DONE is terminal");
    }

    // =====================================================================================================
    // H3: the PUBLIC transition, regardless of remaining PRIVATE references
    // =====================================================================================================

    @Test
    void releasingTheLastPublicReferenceEnqueuesEvenWhilePrivateReferencesRemainAndTheObjectStaysReferenced() throws Exception {
        Seed seed = seed("PUBLIC", "PRIVATE");

        release(seed, "r0");

        assertEquals(1, taskCount(seed), "the CDN exposure ended with the last PUBLIC reference");
        assertTrue(jdbc.queryForList("SELECT unreferenced_since FROM eatwhat_file.file_tombstone WHERE file_id=?", seed.fileId())
                .stream().allMatch(row -> row.get("unreferenced_since") == null), "the revision is still referenced");
        FileGcService gc = gcService();
        assertEquals(0, gc.detect().unreferencedRevisions(), "not GC-eligible: still referenced");
        assertEquals(0, gc.run().deleted());
        assertTrue(exists(seed.finalKey()));
    }

    @Test
    void releasingOnlyThePrivateReferenceEnqueuesNothing() throws Exception {
        Seed seed = seed("PUBLIC", "PRIVATE");

        release(seed, "r1");

        assertEquals(0, taskCount(seed));
    }

    @Test
    void aRepeatedReleaseOfAnAlreadyReleasedPublicReferenceDoesNotEnqueueAgain() throws Exception {
        Seed seed = seed("PUBLIC", "PRIVATE");
        release(seed, "r0");
        jdbc.update("UPDATE eatwhat_file.file_cdn_invalidation SET status='DONE', completed_at=clock_timestamp() WHERE file_id=?", seed.fileId());

        release(seed, "r0");

        assertEquals(1, taskCount(seed));
    }

    @Test
    void restoringAndReleasingThePublicReferenceAgainCreatesGenerationTwoWhilePrivateRemains() throws Exception {
        Seed seed = seed("PUBLIC", "PRIVATE");
        release(seed, "r0");
        jdbc.update("UPDATE eatwhat_file.file_cdn_invalidation SET status='DONE', completed_at=clock_timestamp() WHERE file_id=?", seed.fileId());
        restore(seed, "r0");

        release(seed, "r0");

        assertEquals(List.of(1, 2), jdbc.queryForList("SELECT generation FROM eatwhat_file.file_cdn_invalidation WHERE file_id=? ORDER BY generation", Integer.class, seed.fileId()));
    }

    // =====================================================================================================
    // H1: no lost revoke between "revived" and the next release
    // =====================================================================================================

    @Test
    void aReleaseWhileTheEarlierTaskIsLeasedGetsItsOwnTaskAndTheGcStaysBlockedUntilThatOneIsDone() throws Exception {
        Seed seed = seed("PUBLIC");
        release(seed, "r0");                                           // T1 READY
        restore(seed, "r0");                                           // revive
        FileCdnInvalidationStore.Task t1 = tasks.claim(5, Duration.ofMinutes(2)).get(0);   // worker holds T1
        release(seed, "r0");                                           // release again: T1 is LEASED, not READY

        assertEquals(2, taskCount(seed), "the trigger inserted T2 although T1 was still LEASED");
        assertEquals("READY", jdbc.queryForObject("SELECT status FROM eatwhat_file.file_cdn_invalidation WHERE file_id=? AND generation=2", String.class, seed.fileId()));

        // T1 now ends: the revision is no longer publicly referenced, so skipIfRevived does not swallow it.
        assertFalse(tasks.skipIfRevived(t1));
        assertTrue(tasks.markDone(t1, "t1"));
        ageTombstone(seed);
        FileGcService gc = gcService();
        gc.detect();

        assertEquals(0, gc.run().deleted());
        assertEquals("CDN_INVALIDATION_PENDING", candidateReason(seed), "T2 is READY");
        assertTrue(exists(seed.finalKey()));

        fake.enqueue(new Accepted("t2"));
        assertEquals(1, worker.runOnce());
        assertEquals("DONE", jdbc.queryForObject("SELECT status FROM eatwhat_file.file_cdn_invalidation WHERE file_id=? AND generation=2", String.class, seed.fileId()));
        assertEquals(1, gc.run().deleted());
    }

    @Test
    void skipIfRevivedIsOneAtomicDecisionAndFencedAndAReleaseAfterItEnqueuesANewTask() throws Exception {
        Seed seed = seed("PUBLIC");
        release(seed, "r0");
        restore(seed, "r0");
        FileCdnInvalidationStore.Task t1 = tasks.claim(5, Duration.ofMinutes(2)).get(0);

        assertTrue(tasks.skipIfRevived(t1), "publicly referenced at the moment of the write");
        assertEquals("REVIVED", task(seed).get("skip_reason"));
        release(seed, "r0");

        assertEquals(2, taskCount(seed), "the next release enqueues a fresh task");
        // The finished task is no longer held, so it cannot skip again even though the revision is public again.
        restore(seed, "r0");
        assertFalse(tasks.skipIfRevived(t1));
    }

    @Test
    void gcRefusesWhenTheLatestRevokeTaskWasSkippedAsRevivedAndNoNewerTaskExists() throws Exception {
        Seed seed = seed("PUBLIC");
        release(seed, "r0");
        ageTombstone(seed);
        jdbc.update("UPDATE eatwhat_file.file_cdn_invalidation SET status='SKIPPED', skip_reason='REVIVED', completed_at=clock_timestamp() WHERE file_id=?", seed.fileId());
        FileGcService gc = gcService();
        gc.detect();

        assertEquals(0, gc.run().deleted());

        assertEquals("CDN_INVALIDATION_PENDING", candidateReason(seed));
        assertTrue(exists(seed.finalKey()));
        // A NO_PROVIDER skip is a different thing and releases the gate.
        jdbc.update("UPDATE eatwhat_file.file_cdn_invalidation SET skip_reason='NO_PROVIDER' WHERE file_id=?", seed.fileId());
        assertEquals(1, gc.run().deleted());
    }

    // =====================================================================================================
    // M2: concurrent releases are serialized per revision
    // =====================================================================================================

    @Test
    void theReleaseTriggerWaitsForThePerRevisionAdvisoryLock() throws Exception {
        Seed seed = seed("PUBLIC");
        try (java.sql.Connection holder = java.sql.DriverManager.getConnection(dataSource.getUrl(), IsolatedContainers.USER, IsolatedContainers.PASSWORD)) {
            holder.setAutoCommit(false);
            try (var statement = holder.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(hashtextextended('" + seed.fileId() + ":1', 0))");
                ExecutorService pool = Executors.newSingleThreadExecutor();
                try {
                    Future<?> releasing = pool.submit(() -> release(seed, "r0"));
                    assertThrows(java.util.concurrent.TimeoutException.class, () -> releasing.get(1500, TimeUnit.MILLISECONDS),
                            "the trigger must wait for the revision's advisory lock before counting live references");
                    holder.commit();
                    releasing.get(30, TimeUnit.SECONDS);
                } finally {
                    pool.shutdownNow();
                }
            }
        }
        assertEquals(1, taskCount(seed));
    }

    @Test
    void twoConcurrentReleasesOfTheTwoPublicReferencesYieldExactlyOneTask() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 6; round++) {
                Seed seed = seed("PUBLIC", "PUBLIC");
                java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
                Future<?> a = pool.submit(() -> { barrier.await(); release(seed, "r0"); return null; });
                Future<?> b = pool.submit(() -> { barrier.await(); release(seed, "r1"); return null; });
                a.get(30, TimeUnit.SECONDS);
                b.get(30, TimeUnit.SECONDS);

                assertEquals(1, taskCount(seed), "round " + round + ": neither lost nor duplicated");
                assertNotNull(jdbc.queryForObject("SELECT unreferenced_since FROM eatwhat_file.file_tombstone WHERE file_id=?", Object.class, seed.fileId()),
                        "round " + round + ": the tombstone saw zero live references");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // =====================================================================================================
    // L1: the V32 backfill statement
    // =====================================================================================================

    @Test
    void backfillCreatesOneReadyRevokeTaskForAlreadyUnreferencedPublicRevisionsOnly() throws Exception {
        String migration = new String(getClass().getClassLoader().getResourceAsStream("db/migration/V32__file_cdn_invalidation.sql").readAllBytes(), StandardCharsets.UTF_8);
        String backfill = migration.substring(migration.indexOf("-- BACKFILL-BEGIN") + "-- BACKFILL-BEGIN".length(), migration.indexOf("-- BACKFILL-END")).trim();
        assertTrue(backfill.toUpperCase().startsWith("INSERT INTO EATWHAT_FILE.FILE_CDN_INVALIDATION"), backfill);

        // "Pre-V32" shapes: unreferenced + public history + no task.
        Seed legacy = seed("PRIVATE");
        release(legacy, "r0");
        jdbc.update("UPDATE eatwhat_file.file_tombstone SET had_public_ref=true WHERE file_id=?", legacy.fileId());
        // Already covered by a task: untouched.
        Seed covered = seed("PUBLIC");
        release(covered, "r0");
        // Still referenced: untouched.
        Seed referenced = seed("PUBLIC");
        jdbc.update("INSERT INTO eatwhat_file.file_tombstone(file_id, revision, unreferenced_since, had_public_ref) VALUES (?,1,NULL,true)", referenced.fileId());
        // Never public: untouched.
        Seed privateOnly = seed("PRIVATE");
        release(privateOnly, "r0");

        assertEquals(0, taskCount(legacy));
        jdbc.execute(backfill);

        assertEquals(1, taskCount(legacy));
        Map<String, Object> row = task(legacy);
        assertEquals("REVOKE", row.get("reason"));
        assertEquals("READY", row.get("status"));
        assertEquals(1, row.get("generation"));
        assertEquals(1, taskCount(covered));
        assertEquals(0, taskCount(referenced));
        assertEquals(0, taskCount(privateOnly));
        jdbc.execute(backfill);
        assertEquals(1, taskCount(legacy), "idempotent");
    }

    // =====================================================================================================
    // worker
    // =====================================================================================================

    @Test
    void noopProviderSkipsWithNoProvider() throws Exception {
        Seed seed = seed("PUBLIC");
        release(seed, "r0");
        FileCdnInvalidationWorker noop = new FileCdnInvalidationWorker(tasks, new NoopCdnProvider(), props, () -> PUBLIC_BASE, new Random(1));

        assertEquals(1, noop.runOnce());

        Map<String, Object> row = task(seed);
        assertEquals("SKIPPED", row.get("status"));
        assertEquals("NO_PROVIDER", row.get("skip_reason"));
        assertNotNull(row.get("completed_at"));
        assertNull(row.get("lease_until"));
    }

    @Test
    void acceptedSubmissionIsDoneWithTheProviderRequestIdAndACdnRelativePath() throws Exception {
        Seed seed = seed("PUBLIC");
        release(seed, "r0");
        fake.enqueue(new Accepted("req-123"));

        assertEquals(1, worker.runOnce());

        Map<String, Object> row = task(seed);
        assertEquals("DONE", row.get("status"));
        assertEquals("req-123", row.get("provider_request_id"));
        assertEquals(1, row.get("attempts"));
        assertEquals(1, fake.received().size());
        var received = fake.received().get(0);
        assertEquals(Reason.REVOKE, received.reason());
        assertEquals(List.of("/pub/" + seed.finalKey()), received.paths(), "host-less path under the public base path");
        assertEquals(row.get("task_id"), received.taskId());
        assertEquals(received.paths().get(0), CdnPaths.relativePath(PUBLIC_BASE, seed.finalKey()));
    }

    @Test
    void retryableSubmissionGoesBackToReadyWithBackoff() throws Exception {
        Seed seed = seed("PUBLIC");
        release(seed, "r0");
        fake.enqueue(new Retryable("HTTP_503"));

        worker.runOnce();

        Map<String, Object> row = task(seed);
        assertEquals("READY", row.get("status"));
        assertEquals(1, row.get("attempts"));
        assertEquals("HTTP_503", row.get("last_error"));
        assertNull(row.get("lease_until"));
        long secondsAhead = jdbc.queryForObject("SELECT extract(epoch FROM (next_attempt_at - clock_timestamp()))::bigint FROM eatwhat_file.file_cdn_invalidation WHERE file_id=?", Long.class, seed.fileId());
        assertTrue(secondsAhead > 20 && secondsAhead <= 30, "next attempt is in the future by about retry-base: " + secondsAhead);
        assertEquals(0, worker.runOnce(), "not due yet");
    }

    @Test
    void retryExhaustionAlertsExactlyOnceAndKeepsRetrying() throws Exception {
        Seed seed = seed("PUBLIC");
        release(seed, "r0");
        fake.alwaysReturn(new Retryable("HTTP_503"));

        for (int attempt = 1; attempt <= 4; attempt++) {
            makeDue(seed);
            assertEquals(1, worker.runOnce());
            Map<String, Object> row = task(seed);
            assertEquals("READY", row.get("status"), "never gives up on its own");
            assertEquals(attempt, row.get("attempts"));
        }

        assertEquals(4, fake.received().size());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_replica_alert WHERE file_id=? AND alert_type='CDN_RETRY_EXHAUSTED' AND site='LOCAL'", Integer.class, seed.fileId()),
                "ONE alert, written when attempts reach alert-after-attempts (2)");
    }

    @Test
    void permanentFailureQuarantinesAndAlerts() throws Exception {
        Seed seed = seed("PUBLIC");
        release(seed, "r0");
        fake.enqueue(new Permanent("HTTP_400"));

        worker.runOnce();

        Map<String, Object> row = task(seed);
        assertEquals("QUARANTINED", row.get("status"));
        assertEquals("HTTP_400", row.get("last_error"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_replica_alert WHERE file_id=? AND alert_type='CDN_INVALIDATION_FAILED'", Integer.class, seed.fileId()));
        assertEquals(0, worker.runOnce(), "a quarantined task is not retried");
    }

    @Test
    void aThrowingProviderIsTreatedAsRetryableAndLeaksNoExceptionText() throws Exception {
        Seed seed = seed("PUBLIC");
        release(seed, "r0");
        fake.alwaysThrow(new IllegalStateException("secret-looking-detail https://user:pw@cdn.example"));

        worker.runOnce();

        Map<String, Object> row = task(seed);
        assertEquals("READY", row.get("status"));
        assertEquals("PROVIDER_ERROR", row.get("last_error"));
    }

    @Test
    void revivedBeforeSubmitIsSkippedWithoutCallingTheProvider() throws Exception {
        Seed seed = seed("PUBLIC");
        release(seed, "r0");
        restore(seed, "r0"); // live PUBLIC reference again

        worker.runOnce();

        Map<String, Object> row = task(seed);
        assertEquals("SKIPPED", row.get("status"));
        assertEquals("REVIVED", row.get("skip_reason"));
        assertTrue(fake.received().isEmpty());
    }

    @Test
    void staleFencingTokenChangesNothing() throws Exception {
        Seed seed = seed("PUBLIC");
        release(seed, "r0");
        FileCdnInvalidationStore.Task first = tasks.claim(5, Duration.ofMinutes(2)).get(0);
        assertEquals(1, first.fencingToken());
        assertTrue(tasks.claim(5, Duration.ofMinutes(2)).isEmpty(), "a live lease cannot be claimed again");
        jdbc.update("UPDATE eatwhat_file.file_cdn_invalidation SET lease_until=clock_timestamp() - interval '1 second' WHERE file_id=?", seed.fileId());
        FileCdnInvalidationStore.Task second = tasks.claim(5, Duration.ofMinutes(2)).get(0);
        assertEquals(2, second.fencingToken());
        assertEquals(2, second.attempts());
        Map<String, Object> before = task(seed);

        assertFalse(tasks.markDone(first, "stale"));
        assertFalse(tasks.markSkipped(first, "STALE"));
        assertFalse(tasks.retry(first, Duration.ofSeconds(1), "STALE"));
        assertFalse(tasks.quarantine(first, "STALE"));
        assertFalse(tasks.alertRetryExhausted(first));

        assertEquals(before, task(seed), "the stale holder changed nothing");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_replica_alert WHERE file_id=?", Integer.class, seed.fileId()));
        assertTrue(tasks.markDone(second, "ok"));
        assertEquals("DONE", task(seed).get("status"));
    }

    @Test
    void twoWorkersClaimDisjointTasks() throws Exception {
        for (int i = 0; i < 6; i++) {
            Seed seed = seed("PUBLIC");
            release(seed, "r0");
        }
        FileCdnInvalidationStore otherStore = new FileCdnInvalidationStore(new JdbcTemplate(new DriverManagerDataSource(
                dataSource.getUrl(), IsolatedContainers.USER, IsolatedContainers.PASSWORD)));
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<List<FileCdnInvalidationStore.Task>> a = pool.submit(() -> { go.await(); return tasks.claim(3, Duration.ofMinutes(2)); });
            Future<List<FileCdnInvalidationStore.Task>> b = pool.submit(() -> { go.await(); return otherStore.claim(3, Duration.ofMinutes(2)); });
            go.countDown();
            List<FileCdnInvalidationStore.Task> first = a.get(30, TimeUnit.SECONDS);
            List<FileCdnInvalidationStore.Task> second = b.get(30, TimeUnit.SECONDS);
            Set<UUID> ids = new LinkedHashSet<>();
            first.forEach(t -> ids.add(t.taskId()));
            second.forEach(t -> ids.add(t.taskId()));
            assertEquals(first.size() + second.size(), ids.size(), "no task was claimed twice");
            assertEquals(6, ids.size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void crashedWorkersExpiredLeaseIsReclaimed() throws Exception {
        Seed seed = seed("PUBLIC");
        release(seed, "r0");
        FileCdnInvalidationStore.Task lost = tasks.claim(5, Duration.ofMinutes(2)).get(0); // "crash": never processed
        jdbc.update("UPDATE eatwhat_file.file_cdn_invalidation SET lease_until=clock_timestamp() - interval '1 second' WHERE task_id=?", lost.taskId());
        fake.enqueue(new Accepted("after-crash"));

        assertEquals(1, worker.runOnce());

        Map<String, Object> row = task(seed);
        assertEquals("DONE", row.get("status"));
        assertEquals(2, row.get("attempts"));
        assertEquals(2L, row.get("fencing_token"));
        assertFalse(tasks.markDone(lost, "late"), "the crashed holder is fenced out");
    }

    // =====================================================================================================
    // webhook provider against a local HTTP server
    // =====================================================================================================

    @Test
    void webhookProviderSendsASignedBodyWithCdnRelativePathsOnly() throws Exception {
        String secret = "synthetic-webhook-secret";
        try (TestServer server = new TestServer(200, "{\"requestId\":\"body-req-9\"}", 0)) {
            HttpWebhookCdnProvider provider = new HttpWebhookCdnProvider(server.uri(), secret, Duration.ofSeconds(5));
            UUID taskId = UUID.randomUUID();
            List<String> paths = List.of(CdnPaths.relativePath(PUBLIC_BASE, "verified/abc def"), CdnPaths.relativePath(PUBLIC_BASE, "verified/xyz"));

            var result = provider.submit(paths, Reason.REVOKE, taskId);

            assertEquals(new Accepted("body-req-9"), result);
            assertEquals(1, server.bodies.size());
            byte[] raw = server.bodies.get(0);
            JsonNode body = JSON.readTree(raw);
            assertEquals(taskId.toString(), body.get("taskId").asText());
            assertEquals("REVOKE", body.get("reason").asText());
            assertEquals(2, body.get("paths").size());
            for (JsonNode path : body.get("paths")) {
                String value = path.asText();
                assertTrue(value.startsWith("/pub/verified/"), value);
                assertFalse(value.contains("://") || value.contains("?") || value.contains("X-Amz-") || value.contains("Signature") || value.contains("cdn.example.test"), value);
            }
            assertEquals("/pub/verified/abc%20def", body.get("paths").get(0).asText(), "same encoding as the public URLs");
            String signature = server.headers.get(0).get(HttpWebhookCdnProvider.SIGNATURE_HEADER.toLowerCase());
            assertEquals(HttpWebhookCdnProvider.sign(secret.getBytes(StandardCharsets.UTF_8), raw), signature, "HMAC-SHA256 over the raw body");
            assertNotEquals(HttpWebhookCdnProvider.sign("another-secret".getBytes(StandardCharsets.UTF_8), raw), signature);
            assertEquals(String.valueOf(body.get("timestamp").asLong()), server.headers.get(0).get(HttpWebhookCdnProvider.TIMESTAMP_HEADER.toLowerCase()));
            assertFalse(new String(raw, StandardCharsets.UTF_8).contains(secret), "the secret is never in the body");
        }
    }

    @Test
    void webhookRequestIdFromHeaderAndStatusClassification() throws Exception {
        try (TestServer server = new TestServer(204, "", 0)) {
            server.responseHeaders.put("X-Request-Id", "hdr-1");
            assertEquals(new Accepted("hdr-1"), new HttpWebhookCdnProvider(server.uri(), "s", Duration.ofSeconds(5)).submit(List.of("/a"), Reason.DELETE, UUID.randomUUID()));
        }
        try (TestServer server = new TestServer(200, "", 0)) {
            assertEquals(new Accepted(null), new HttpWebhookCdnProvider(server.uri(), "s", Duration.ofSeconds(5)).submit(List.of("/a"), Reason.REVOKE, UUID.randomUUID()));
        }
        for (int status : new int[]{503, 500, 429}) {
            try (TestServer server = new TestServer(status, "x", 0)) {
                assertEquals(new Retryable("HTTP_" + status), new HttpWebhookCdnProvider(server.uri(), "s", Duration.ofSeconds(5)).submit(List.of("/a"), Reason.REVOKE, UUID.randomUUID()), "status " + status);
            }
        }
        for (int status : new int[]{400, 401, 403, 404, 302}) {
            try (TestServer server = new TestServer(status, "x", 0)) {
                assertEquals(new Permanent("HTTP_" + status), new HttpWebhookCdnProvider(server.uri(), "s", Duration.ofSeconds(5)).submit(List.of("/a"), Reason.REVOKE, UUID.randomUUID()), "status " + status);
            }
        }
    }

    @Test
    void webhookTimeoutAndConnectionFailureAreRetryable() throws Exception {
        try (TestServer slow = new TestServer(200, "", 3000)) {
            long started = System.nanoTime();
            var result = new HttpWebhookCdnProvider(slow.uri(), "s", Duration.ofSeconds(1)).submit(List.of("/a"), Reason.REVOKE, UUID.randomUUID());
            assertEquals(new Retryable("TIMEOUT"), result);
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 3, "the timeout bounds the call");
        }
        URI closed;
        try (TestServer gone = new TestServer(200, "", 0)) {
            closed = gone.uri();
        }
        assertInstanceOf(Retryable.class, new HttpWebhookCdnProvider(closed, "s", Duration.ofSeconds(2)).submit(List.of("/a"), Reason.REVOKE, UUID.randomUUID()));
    }

    @Test
    void workerWithTheWebhookProviderSendsTheDerivedRelativePath() throws Exception {
        Seed seed = seed("PUBLIC");
        release(seed, "r0");
        try (TestServer server = new TestServer(200, "", 0)) {
            FileCdnInvalidationWorker webhookWorker = new FileCdnInvalidationWorker(tasks,
                    new HttpWebhookCdnProvider(server.uri(), "synthetic", Duration.ofSeconds(5)), props, () -> PUBLIC_BASE, new Random(1));

            webhookWorker.runOnce();

            assertEquals("DONE", task(seed).get("status"));
            JsonNode body = JSON.readTree(server.bodies.get(0));
            assertEquals("/pub/" + seed.finalKey(), body.get("paths").get(0).asText());
        }
    }

    @Test
    void webhookNeverBuffersAHugeResponseBodyAndStillClassifiesByStatus() throws Exception {
        byte[] chunk = new byte[64 * 1024];
        java.util.Arrays.fill(chunk, (byte) 'x');
        BodyServer.Writer endless = out -> {
            for (int i = 0; i < 1024; i++) { // up to 64 MiB; the client stops reading after a few KiB
                out.write(chunk);
                out.flush();
            }
        };
        for (int status : new int[]{200, 500, 400}) {
            try (BodyServer server = new BodyServer(status, endless)) {
                long started = System.nanoTime();
                var result = new HttpWebhookCdnProvider(server.uri(), "s", Duration.ofSeconds(5)).submit(List.of("/a"), Reason.REVOKE, UUID.randomUUID());
                long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);

                assertTrue(seconds < 4, "bounded: " + seconds + "s for status " + status);
                assertEquals(status == 200 ? new Accepted(null) : status == 500 ? new Retryable("HTTP_500") : new Permanent("HTTP_400"), result);
            }
        }
    }

    @Test
    void webhookSlowDripBodyEndsWithinTheTimeoutAsRetryable() throws Exception {
        BodyServer.Writer drip = out -> {
            for (int i = 0; i < 60; i++) {
                out.write('{');
                out.flush();
                Thread.sleep(300);
            }
        };
        try (BodyServer server = new BodyServer(200, drip)) {
            long started = System.nanoTime();
            var result = new HttpWebhookCdnProvider(server.uri(), "s", Duration.ofSeconds(1)).submit(List.of("/a"), Reason.REVOKE, UUID.randomUUID());
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertEquals(new Retryable("TIMEOUT"), result);
            assertTrue(millis < 3000, "the deadline covers the body read: " + millis + "ms");
        }
    }

    // =====================================================================================================
    // T12 and the end-to-end chain with the GC
    // =====================================================================================================

    @Test
    void privateOnlyFileNeverYieldsACdnUrlNorATask() throws Exception {
        Seed seed = seed("PRIVATE");
        makeGloballyAvailable(seed);
        try (S3CompatibleStorageService storage = storageService()) {
            String url = storage.signGetUrl(seed.logicalKey());
            assertTrue(url == null || !url.startsWith("https://cdn.example.test"), "no CDN URL for a private-only file: " + url);
            release(seed, "r0");
            assertNull(storage.signGetUrl(seed.logicalKey()));
        }
        assertEquals(0, taskCount(seed));
    }

    @Test
    void revokedPublicFileYieldsNoCdnUrlImmediatelyEvenWhileTheTaskIsStillReady() throws Exception {
        Seed seed = seed("PUBLIC");
        makeGloballyAvailable(seed);
        try (S3CompatibleStorageService storage = storageService()) {
            String before = storage.signGetUrl(seed.logicalKey());
            assertEquals("https://cdn.example.test/pub/" + seed.finalKey(), before, "control: a live public reference gets the CDN URL");

            release(seed, "r0");

            assertEquals("READY", task(seed).get("status"), "the invalidation has not run");
            assertNull(storage.signGetUrl(seed.logicalKey()), "the source stops handing out the URL at the release commit");
        }
    }

    @Test
    void publicRevisionIsDeletedOnlyAfterItsInvalidationIsDone_viaTheTwoPhaseFlow() throws Exception {
        Seed seed = seed("PUBLIC");
        release(seed, "r0");
        ageTombstone(seed);
        FileGcService gc = gcService();
        FileAliasResolver aliases = new FileAliasResolver(jdbc);
        gc.detect();

        // READY task: blocked, object untouched.
        var blocked = gc.run();
        assertEquals(0, blocked.deleted());
        assertEquals("CDN_INVALIDATION_PENDING", candidateReason(seed));
        assertTrue(exists(seed.finalKey()));
        assertNull(aliases.resolve(seed.logicalKey()).readableObjectKey());

        // The worker (fake provider) completes the invalidation.
        fake.enqueue(new Accepted("fake-1"));
        assertEquals(1, worker.runOnce());
        assertEquals("DONE", task(seed).get("status"));

        var deleted = gc.run();

        assertEquals(1, deleted.deleted());
        assertFalse(exists(seed.finalKey()));
        assertEquals("DELETED", replicaState(seed));
        assertEquals("DELETED", candidateState(seed));
        assertNull(aliases.resolve(seed.logicalKey()).readableObjectKey(), "the resolver never returns a URL again");
        assertNull(new FileLifecycleService(jdbc, null, null, null, tx).resolveAlias(seed.logicalKey()));
        // The only task the GC created is the DELETE invalidation of the now-missing object, atomically with phase 1.
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_cdn_invalidation WHERE file_id=? AND reason='REVOKE'", Integer.class, seed.fileId()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_cdn_invalidation WHERE file_id=? AND reason='DELETE' AND status='READY'", Integer.class, seed.fileId()));

        // The worker processes it like any other task: the path comes from final_key, which is still in file_revision.
        fake.enqueue(new Accepted("fake-delete-1"));
        assertEquals(1, worker.runOnce());
        assertEquals("DONE", jdbc.queryForObject("SELECT status FROM eatwhat_file.file_cdn_invalidation WHERE file_id=? AND reason='DELETE'", String.class, seed.fileId()));
        var lastRequest = fake.received().get(fake.received().size() - 1);
        assertEquals(Reason.DELETE, lastRequest.reason());
        assertEquals(List.of("/pub/" + seed.finalKey()), lastRequest.paths());
        assertEquals(0, gc.run().examined(), "the DELETE task does not hold anything else back");
    }

    @Test
    void noopProviderSkippedTaskAlsoReleasesTheGcGate() throws Exception {
        Seed seed = seed("PUBLIC");
        release(seed, "r0");
        ageTombstone(seed);
        FileGcService gc = gcService();
        gc.detect();
        assertEquals(0, gc.run().deleted());

        new FileCdnInvalidationWorker(tasks, new NoopCdnProvider(), props, () -> PUBLIC_BASE, new Random(1)).runOnce();
        assertEquals("SKIPPED", task(seed).get("status"));

        assertEquals(1, gc.run().deleted());
        assertFalse(exists(seed.finalKey()));
    }

    @Test
    void quarantinedTaskKeepsTheGcBlocked() throws Exception {
        Seed seed = seed("PUBLIC");
        release(seed, "r0");
        ageTombstone(seed);
        fake.enqueue(new Permanent("HTTP_400"));
        worker.runOnce();
        assertEquals("QUARANTINED", task(seed).get("status"));
        FileGcService gc = gcService();
        gc.detect();

        var result = gc.run();

        assertEquals(0, result.deleted());
        assertEquals("CDN_INVALIDATION_FAILED", candidateReason(seed));
        assertTrue(exists(seed.finalKey()));
        assertEquals("AVAILABLE_LOCAL", replicaState(seed));
    }

    // =====================================================================================================
    // helpers
    // =====================================================================================================

    private record Seed(UUID fileId, String finalKey, String logicalKey, List<String> refIds, List<String> visibilities) { }

    /** A local-origin VERIFIED revision with one reference per given visibility (ref ids r0, r1, ...), object in the bucket. */
    private Seed seed(String... visibilities) throws Exception {
        long owner = jdbc.queryForObject("INSERT INTO users(nick_name,password_hash) VALUES (?, 'synthetic') RETURNING id", Long.class, "u" + uuid().substring(0, 20));
        UUID ownerGid = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        String logicalKey = "foods/" + owner + "/" + uuid();
        String finalKey = "verified/" + uuid();
        jdbc.update("INSERT INTO eatwhat_file.upload_intent(intent_id, owner_id, owner_gid, biz, logical_key, staging_key, declared_size, declared_type, expires_at, status)"
                + " VALUES (?,?,?,'foods',?,?,10,?, now() + interval '1 hour','COMPLETE')", UUID.randomUUID(), owner, ownerGid, logicalKey, "staging/" + uuid(), CONTENT_TYPE);
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key) VALUES (?,?, 'foods', ?)", fileId, ownerGid, logicalKey);
        jdbc.update("INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at) VALUES (?,1,?,?,10,?,'VERIFIED', clock_timestamp())",
                fileId, finalKey, SHA, CONTENT_TYPE);
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state) VALUES (?,1,'LOCAL',?,'AVAILABLE_LOCAL')", fileId, finalKey);
        jdbc.update("INSERT INTO eatwhat_file.file_key_alias(logical_key, file_id, revision, owner_gid, biz) VALUES (?,?,1,?, 'foods')", logicalKey, fileId, ownerGid);
        List<String> refIds = new ArrayList<>();
        for (int i = 0; i < visibilities.length; i++) {
            refIds.add("r" + i);
            jdbc.update("INSERT INTO eatwhat_file.file_reference(logical_key, ref_kind, ref_id, visibility) VALUES (?, 'food', ?, ?)", logicalKey, "r" + i, visibilities[i]);
        }
        Path file = Files.createTempFile("cdn-", ".bin");
        try {
            Files.write(file, new byte[10]);
            realStore.putFile(finalKey, CONTENT_TYPE, file, SHA);
        } finally {
            Files.deleteIfExists(file);
        }
        return new Seed(fileId, finalKey, logicalKey, refIds, List.of(visibilities));
    }

    private void release(Seed seed, String refId) {
        jdbc.update("INSERT INTO eatwhat_file.file_reference_event(logical_key, ref_kind, ref_id, action, reason) VALUES (?, 'food', ?, 'RELEASE', 'ENTITY_DELETED')", seed.logicalKey(), refId);
    }

    private void restore(Seed seed, String refId) {
        jdbc.update("INSERT INTO eatwhat_file.file_reference_event(logical_key, ref_kind, ref_id, action, reason) VALUES (?, 'food', ?, 'RESTORE', 'ADMIN')", seed.logicalKey(), refId);
    }

    private int taskCount(Seed seed) {
        return jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_cdn_invalidation WHERE file_id=?", Integer.class, seed.fileId());
    }

    private Map<String, Object> task(Seed seed) {
        return jdbc.queryForMap("SELECT * FROM eatwhat_file.file_cdn_invalidation WHERE file_id=? ORDER BY generation DESC LIMIT 1", seed.fileId());
    }

    private void makeDue(Seed seed) {
        jdbc.update("UPDATE eatwhat_file.file_cdn_invalidation SET next_attempt_at=clock_timestamp() WHERE file_id=? AND status='READY'", seed.fileId());
    }

    private void ageTombstone(Seed seed) {
        assertEquals(1, jdbc.update("UPDATE eatwhat_file.file_tombstone SET unreferenced_since=clock_timestamp() - interval '1 hour' WHERE file_id=?", seed.fileId()));
    }

    private void makeGloballyAvailable(Seed seed) {
        jdbc.update("INSERT INTO eatwhat_file.file_publication(file_id, revision, global_state, peer_confirmed_at) VALUES (?,1,'AVAILABLE_GLOBAL', clock_timestamp())", seed.fileId());
    }

    private boolean exists(String key) {
        return realStore.head(key).isPresent();
    }

    private String replicaState(Seed seed) {
        return jdbc.queryForObject("SELECT state FROM eatwhat_file.file_replica WHERE file_id=? AND site='LOCAL'", String.class, seed.fileId());
    }

    private String candidateState(Seed seed) {
        return jdbc.queryForObject("SELECT state FROM eatwhat_file.file_gc_candidate WHERE object_key=?", String.class, seed.finalKey());
    }

    private String candidateReason(Seed seed) {
        return jdbc.queryForObject("SELECT block_reason FROM eatwhat_file.file_gc_candidate WHERE object_key=?", String.class, seed.finalKey());
    }

    private FileGcService gcService() {
        FileGcProperties gc = new FileGcProperties();
        gc.setEnabled(true);
        gc.setInventoryEnabled(true);
        gc.setSafetyWindow(Duration.ofSeconds(1));
        gc.setRetention(Duration.ofSeconds(1));
        gc.setDryRun(false);
        gc.setExecuteEnabled(true);
        gc.setExecuteClasses(new LinkedHashSet<>(FileGcProperties.EXECUTE_CLASSES));
        gc.setExecuteAck("drill-cdn");
        gc.setGcInterval(Duration.ZERO);
        SiteProperties site = new SiteProperties();
        site.setMode(SiteProperties.Mode.CAMPUS);
        return new FileGcService(jdbc, tx, gc, site, () -> realStore);
    }

    private S3CompatibleStorageService storageService() {
        S3StorageProperties s3 = new S3StorageProperties();
        s3.setProvider(S3StorageProperties.Provider.RUSTFS);
        s3.setEndpoint("http://localhost:9000");
        s3.setRegion(REGION);
        s3.setBucket("unused-bucket");
        s3.setAccessKey(IsolatedContainers.RUSTFS_ACCESS_KEY);
        s3.setSecretKey(IsolatedContainers.RUSTFS_SECRET_KEY);
        s3.setPublicBaseUrl(PUBLIC_BASE);
        FileReplicationProperties replication = new FileReplicationProperties();
        replication.setCdnOutputEnabled(true);
        FileUrlResolver urls = new FileUrlResolver(jdbc, replication, new ReplicationConsentGate() {
            @Override public boolean replicationAllowed(UUID ownerGid) { return true; }
            @Override public Long currentEpoch(UUID ownerGid) { return 1L; }
        });
        S3CompatibleStorageService storage = new S3CompatibleStorageService(s3, new FileAliasResolver(jdbc));
        storage.setFileUrlResolver(urls);
        return storage;
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Server whose response body is produced by a writer (to model huge or slowly dripping bodies). */
    private static final class BodyServer implements AutoCloseable {
        interface Writer {
            void write(java.io.OutputStream out) throws Exception;
        }

        private final HttpServer server;

        BodyServer(int status, Writer writer) throws Exception {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/hook", exchange -> {
                exchange.getRequestBody().readAllBytes();
                try {
                    exchange.sendResponseHeaders(status, 0); // chunked
                    writer.write(exchange.getResponseBody());
                } catch (Exception clientGone) {
                    // the client stopped reading or timed out
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** Local HTTP server answering every POST with a fixed status/body (optionally after a delay) and recording requests. */
    private static final class TestServer implements AutoCloseable {
        final List<byte[]> bodies = new CopyOnWriteArrayList<>();
        final List<Map<String, String>> headers = new CopyOnWriteArrayList<>();
        final Map<String, String> responseHeaders = new java.util.concurrent.ConcurrentHashMap<>();
        private final HttpServer server;
        private final AtomicInteger hits = new AtomicInteger();

        TestServer(int status, String responseBody, long delayMillis) throws Exception {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/hook", exchange -> {
                hits.incrementAndGet();
                bodies.add(exchange.getRequestBody().readAllBytes());
                Map<String, String> seen = new java.util.HashMap<>();
                exchange.getRequestHeaders().forEach((k, v) -> seen.put(k.toLowerCase(), v.get(0)));
                headers.add(seen);
                if (delayMillis > 0) {
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                responseHeaders.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                    if (bytes.length > 0) exchange.getResponseBody().write(bytes);
                } catch (java.io.IOException clientGone) {
                    // the client timed out first
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
