package you.v50to.eatwhat.integration;

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
import org.testcontainers.containers.GenericContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import you.v50to.eatwhat.config.FileGcProperties;
import you.v50to.eatwhat.config.FileLifecycleProperties;
import you.v50to.eatwhat.config.S3StorageProperties;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.enums.UploadBiz;
import you.v50to.eatwhat.exception.BizException;
import you.v50to.eatwhat.service.storage.FileAliasResolver;
import you.v50to.eatwhat.service.storage.FileLifecycleService;
import you.v50to.eatwhat.service.storage.FileObjectStorage;
import you.v50to.eatwhat.service.storage.replication.DeletableObjectStore;
import you.v50to.eatwhat.service.storage.replication.FileGcService;
import you.v50to.eatwhat.service.storage.replication.FileGcService.Kind;
import you.v50to.eatwhat.service.storage.replication.FileGcService.Lease;
import you.v50to.eatwhat.service.storage.replication.FileGcService.Mode;
import you.v50to.eatwhat.service.storage.replication.FileGcService.Outcome;
import you.v50to.eatwhat.service.storage.replication.FileGcService.RunResult;
import you.v50to.eatwhat.service.storage.replication.S3ReplicaObjectStore;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Physical GC executor against Postgres and RustFS. Every deletion rule has a negative test that proves the object
 * is still in the bucket; the positive tests prove the object, the replica row and the candidate row all agree.
 */
class FileGcServiceIT {
    private static final String REGION = "us-east-1";
    private static final String CONTENT_TYPE = "application/octet-stream";
    private static final String SHA = "a".repeat(64);
    private static final String TEMPLATE_DATABASE = "gc_template";
    private static final String ACK = "drill-restore-2026-10-14";
    private static final byte[] PNG = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3, 4};

    private static GenericContainer<?> postgres;
    private static GenericContainer<?> rustfs;
    private static String postgresUrlBase;
    private static String endpoint;

    private String url;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager tx;
    private S3ReplicaObjectStore realStore;
    private RecordingStore store;
    private FileGcProperties props;
    private SiteProperties site;
    private FileGcService service;

    @BeforeAll
    static void start() {
        postgres = IsolatedContainers.postgres("gc-test");
        postgres.start();
        postgresUrlBase = IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE);
        rustfs = IsolatedContainers.rustfs("gc-test");
        rustfs.start();
        endpoint = "http://" + rustfs.getHost() + ":" + rustfs.getMappedPort(9000);
        // Migrate once; every test gets its own database cloned from this template.
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

    /** Fresh database and fresh bucket per test. */
    @BeforeEach
    void fresh() {
        String database = "it_" + uuid();
        admin().execute("CREATE DATABASE " + database + " TEMPLATE " + TEMPLATE_DATABASE);
        url = IsolatedContainers.jdbcUrl(postgres, database);
        dataSource = new DriverManagerDataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD);
        jdbc = new JdbcTemplate(dataSource);
        tx = new DataSourceTransactionManager(dataSource);

        String bucket = "gc-" + uuid().substring(0, 16);
        try (S3Client s3 = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build()) {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
        realStore = new S3ReplicaObjectStore(endpoint, REGION, bucket,
                IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY, true);
        store = new RecordingStore(realStore);

        props = new FileGcProperties();
        props.setEnabled(true);
        props.setInventoryEnabled(true);
        props.setSafetyWindow(Duration.ofSeconds(1));
        props.setRetention(Duration.ofSeconds(1));
        props.setDryRun(false);
        props.setExecuteEnabled(true);
        props.setExecuteClasses(new java.util.LinkedHashSet<>(FileGcProperties.EXECUTE_CLASSES));
        props.setExecuteAck(ACK);
        props.setMaxDeletesPerRun(100);
        props.setGcInterval(Duration.ZERO); // BLOCKED/failed candidates are re-evaluated on the next run
        props.setClaimLease(Duration.ofMinutes(5));
        site = new SiteProperties();
        site.setMode(SiteProperties.Mode.CAMPUS);
        service = new FileGcService(jdbc, tx, props, site, () -> store);
    }

    @AfterEach
    void close() {
        realStore.close();
    }

    // =====================================================================================================
    // (a) positive: unreferenced local-origin revision
    // =====================================================================================================

    @Test
    void unreferencedLocalRevisionPastRetentionIsDeletedInExecuteMode() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        assertTrue(exists(seed.finalKey()));
        assertTrue(service.detect().unreferencedRevisions() >= 1);

        RunResult result = service.run();

        assertEquals(Mode.EXECUTE, result.mode());
        assertEquals(1, result.deleted());
        assertEquals(0, result.errors());
        assertFalse(exists(seed.finalKey()), "the object must be gone from the bucket");
        assertEquals("DELETED", replicaState(seed.fileId()));
        Map<String, Object> candidate = candidate(seed.finalKey());
        assertEquals("DELETED", candidate.get("state"));
        assertNotNull(candidate.get("deleted_at"));
        assertNull(candidate.get("lease_until"));
        assertNull(new FileAliasResolver(jdbc).resolve(seed.logicalKey()).readableObjectKey(), "the alias no longer resolves");
        assertNull(new FileLifecycleService(jdbc, null, null, null, tx).resolveAlias(seed.logicalKey()));
        assertEquals(1, store.deletes.get());

        Map<String, Object> run = jdbc.queryForMap("SELECT * FROM eatwhat_file.file_gc_run WHERE run_id=?", result.runId());
        assertEquals("EXECUTE", run.get("mode"));
        assertEquals("LOCAL", run.get("site"));
        assertEquals(1L, run.get("listed_count"));
        assertEquals(1L, run.get("finding_count"), "finding_count is the deleted count in EXECUTE mode");
        assertEquals(0L, run.get("error_count"));
        assertNotNull(run.get("finished_at"));
        String snapshot = jdbc.queryForObject("SELECT config_snapshot::text FROM eatwhat_file.file_gc_run WHERE run_id=?", String.class, result.runId());
        assertFalse(snapshot.contains(ACK), "the ack value must never be recorded");
        assertEquals("true", jdbc.queryForObject("SELECT config_snapshot->>'ackPresent' FROM eatwhat_file.file_gc_run WHERE run_id=?", String.class, result.runId()));
        assertEquals("false", jdbc.queryForObject("SELECT config_snapshot->>'dryRun' FROM eatwhat_file.file_gc_run WHERE run_id=?", String.class, result.runId()));
        assertEquals("100", jdbc.queryForObject("SELECT config_snapshot->>'maxDeletesPerRun' FROM eatwhat_file.file_gc_run WHERE run_id=?", String.class, result.runId()));
    }

    @Test
    void referenceCanNeverBeAttachedToADeletedObject() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        service.detect();
        service.run();
        assertEquals("DELETED", replicaState(seed.fileId()));

        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO eatwhat_file.file_reference(logical_key, ref_kind, ref_id, visibility) VALUES (?, 'food', 'again', 'PRIVATE')",
                seed.logicalKey()));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO eatwhat_file.file_reference_event(logical_key, ref_kind, ref_id, action, reason) VALUES (?, 'food', 'r1', 'RESTORE', 'ADMIN')",
                seed.logicalKey()));
        FileLifecycleService lifecycle = new FileLifecycleService(jdbc, null, null, null, tx);
        BizException refused = assertThrows(BizException.class,
                () -> lifecycle.finalizeAndReference(seed.ownerId(), UploadBiz.FOODS, seed.logicalKey(), "food", "again"),
                "the service turns the case into a validation error");
        assertEquals(BizCode.PARAM_INVALID, refused.getBizCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_reference WHERE logical_key=?", Integer.class, seed.logicalKey()),
                "no new reference row exists");
    }

    // =====================================================================================================
    // (b) negatives, one per UNREFERENCED_REVISION rule: the object must survive
    // =====================================================================================================

    @Test
    void retentionNotElapsedIsBlocked() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        jdbc.update("UPDATE eatwhat_file.file_tombstone SET unreferenced_since=clock_timestamp() - interval '10 seconds' WHERE file_id=?", seed.fileId());
        service.detect();
        props.setRetention(Duration.ofHours(1));

        RunResult result = service.run();

        assertSurvives(seed, result, "BLOCKED", "RETENTION_NOT_ELAPSED");
    }

    @Test
    void liveReferenceRevivesAndNeverDeletes() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        service.detect();
        // Re-reference after detection: restores the tombstone (unreferenced_since cleared) and adds a live row.
        jdbc.update("INSERT INTO eatwhat_file.file_reference(logical_key, ref_kind, ref_id, visibility) VALUES (?, 'food', 'r2', 'PRIVATE')", seed.logicalKey());

        RunResult result = service.run();

        assertSurvives(seed, result, "REVIVED", "LIVE_REFERENCE");
        assertEquals(1, result.revived());

        // Unreferenced again later: REVIVED is re-opened by detect() and can then be deleted.
        jdbc.update("INSERT INTO eatwhat_file.file_reference_event(logical_key, ref_kind, ref_id, action, reason) VALUES (?, 'food', 'r2', 'RELEASE', 'ENTITY_DELETED')", seed.logicalKey());
        jdbc.update("INSERT INTO eatwhat_file.file_reference_event(logical_key, ref_kind, ref_id, action, reason) VALUES (?, 'food', 'r1', 'RELEASE', 'ENTITY_DELETED')", seed.logicalKey());
        ageTombstone(seed);
        service.detect();
        assertEquals("DETECTED", candidate(seed.finalKey()).get("state"));
        assertEquals(1, service.run().deleted());
        assertFalse(exists(seed.finalKey()));
    }

    @Test
    void liveReferenceOnAnyAliasOfTheRevisionRevives() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        service.detect();
        // A second logical key aliasing the same (file_id, revision) still holds a live reference.
        // Aliases are immutable once created and must be inserted before the live reference.
        String other = "foods/" + uuid();
        jdbc.update("INSERT INTO eatwhat_file.file_key_alias(logical_key, file_id, revision, owner_gid, biz) VALUES (?, ?, 1, ?, 'foods')",
                other, seed.fileId(), seed.ownerGid());
        jdbc.update("INSERT INTO eatwhat_file.file_reference(logical_key, ref_kind, ref_id, visibility) VALUES (?, 'food', 'x', 'PRIVATE')", other);
        // Force the tombstone back to its stale state to prove the live-reference re-read is independent of it.
        jdbc.update("UPDATE eatwhat_file.file_tombstone SET unreferenced_since=clock_timestamp() - interval '1 hour' WHERE file_id=?", seed.fileId());

        RunResult result = service.run();

        assertSurvives(seed, result, "REVIVED", "LIVE_REFERENCE");
    }

    @Test
    void readyTransferJobBlocks() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        jdbc.update("INSERT INTO eatwhat_file.file_transfer_job(job_id, file_id, revision, target_site, kind, status) VALUES (?,?,1,'PEER','COPY_TO_PEER','READY')",
                UUID.randomUUID(), seed.fileId());
        service.detect();

        assertSurvives(seed, service.run(), "BLOCKED", "TRANSFER_PENDING");
    }

    @Test
    void leasedTransferJobBlocks() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        jdbc.update("INSERT INTO eatwhat_file.file_transfer_job(job_id, file_id, revision, target_site, kind, status, lease_until) VALUES (?,?,1,'PEER','COPY_TO_PEER','LEASED', now() + interval '1 hour')",
                UUID.randomUUID(), seed.fileId());
        service.detect();

        assertSurvives(seed, service.run(), "BLOCKED", "TRANSFER_PENDING");
    }

    @Test
    void peerReplicaBlocksWithPeerAckUnavailable() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at) VALUES (?,1,'PEER',?, 'PEER_VERIFIED', ?, 10, clock_timestamp())",
                seed.fileId(), seed.finalKey(), SHA);
        service.detect();

        assertSurvives(seed, service.run(), "BLOCKED", "PEER_ACK_UNAVAILABLE");
        assertEquals("PEER_VERIFIED", jdbc.queryForObject("SELECT state FROM eatwhat_file.file_replica WHERE file_id=? AND site='PEER'", String.class, seed.fileId()));
    }

    @Test
    void pendingPeerReplicaAlsoBlocks() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state) VALUES (?,1,'PEER',?, 'PENDING_SYNC')",
                seed.fileId(), seed.finalKey());
        service.detect();

        assertSurvives(seed, service.run(), "BLOCKED", "PEER_ACK_UNAVAILABLE");
    }

    @Test
    void quarantinedReplicaBlocks() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        jdbc.update("UPDATE eatwhat_file.file_replica SET state='QUARANTINED' WHERE file_id=? AND site='LOCAL'", seed.fileId());
        service.detect();

        assertSurvives(seed, service.run(), "BLOCKED", "CONSENT_OR_QUARANTINE_HOLD");
    }

    @Test
    void pausedConsentReplicaBlocks() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        jdbc.update("UPDATE eatwhat_file.file_replica SET state='PAUSED_CONSENT' WHERE file_id=? AND site='LOCAL'", seed.fileId());
        service.detect();

        assertSurvives(seed, service.run(), "BLOCKED", "CONSENT_OR_QUARANTINE_HOLD");
    }

    @Test
    void pausedConsentTransferJobBlocks() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        jdbc.update("INSERT INTO eatwhat_file.file_transfer_job(job_id, file_id, revision, target_site, kind, status) VALUES (?,?,1,'PEER','COPY_TO_PEER','PAUSED_CONSENT')",
                UUID.randomUUID(), seed.fileId());
        service.detect();

        assertSurvives(seed, service.run(), "BLOCKED", "CONSENT_OR_QUARANTINE_HOLD");
    }

    @Test
    void availableGlobalPublicationBlocks() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        jdbc.update("INSERT INTO eatwhat_file.file_publication(file_id, revision, global_state, peer_confirmed_at) VALUES (?,1,'AVAILABLE_GLOBAL', clock_timestamp())", seed.fileId());
        service.detect();

        assertSurvives(seed, service.run(), "BLOCKED", "PUBLICATION_NOT_LOCAL_ONLY");
    }

    @Test
    void localOnlyPublicationDoesNotBlock() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        jdbc.update("INSERT INTO eatwhat_file.file_publication(file_id, revision, global_state) VALUES (?,1,'LOCAL_ONLY')", seed.fileId());
        service.detect();

        assertEquals(1, service.run().deleted());
        assertFalse(exists(seed.finalKey()));
    }

    @Test
    void hadPublicReferenceBlocksWhileTheRevokeTaskIsPending() throws Exception {
        Seed seed = seedUnreferenced(true, "PUBLIC");
        assertTrue(jdbc.queryForObject("SELECT had_public_ref FROM eatwhat_file.file_tombstone WHERE file_id=?", Boolean.class, seed.fileId()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_cdn_invalidation WHERE file_id=? AND reason='REVOKE' AND status='READY'", Integer.class, seed.fileId()),
                "the release enqueued the REVOKE task; with the worker off (default) it stays READY and keeps the GC blocked");
        service.detect();

        assertSurvives(seed, service.run(), "BLOCKED", "CDN_INVALIDATION_PENDING");
    }

    @Test
    void hadPublicReferenceWithoutAnyTaskIsBlockedAsUnavailable() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        jdbc.update("UPDATE eatwhat_file.file_tombstone SET had_public_ref=true WHERE file_id=?", seed.fileId());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_cdn_invalidation WHERE file_id=?", Integer.class, seed.fileId()));
        service.detect();

        assertSurvives(seed, service.run(), "BLOCKED", "CDN_INVALIDATION_UNAVAILABLE");
    }

    @Test
    void latestRevokeTaskStatusDrivesTheCdnGate() throws Exception {
        Seed seed = seedUnreferenced(true, "PUBLIC");
        service.detect();
        assertSurvives(seed, service.run(), "BLOCKED", "CDN_INVALIDATION_PENDING");

        jdbc.update("UPDATE eatwhat_file.file_cdn_invalidation SET status='QUARANTINED', last_error='HTTP_400' WHERE file_id=?", seed.fileId());
        assertSurvives(seed, service.run(), "BLOCKED", "CDN_INVALIDATION_FAILED");

        // DONE releases the gate for that task, but any other READY/LEASED task of the revision still blocks.
        jdbc.update("UPDATE eatwhat_file.file_cdn_invalidation SET status='DONE', completed_at=clock_timestamp() WHERE file_id=?", seed.fileId());
        jdbc.update("INSERT INTO eatwhat_file.file_cdn_invalidation(task_id, file_id, revision, reason, generation) VALUES (?,?,1,'DELETE',1)", UUID.randomUUID(), seed.fileId());
        assertSurvives(seed, service.run(), "BLOCKED", "CDN_INVALIDATION_PENDING");

        jdbc.update("UPDATE eatwhat_file.file_cdn_invalidation SET status='SKIPPED', skip_reason='NO_PROVIDER', completed_at=clock_timestamp() WHERE file_id=? AND reason='DELETE'", seed.fileId());
        assertEquals(1, service.run().deleted(), "latest REVOKE DONE and nothing open: the revision may be deleted");
        assertFalse(exists(seed.finalKey()));
        assertEquals("DELETED", replicaState(seed.fileId()));
    }

    @Test
    void releaseRestoreReleaseAgainSetsUnreferencedSinceAndTheRevisionBecomesACandidate() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        assertNotNull(jdbc.queryForObject("SELECT unreferenced_since FROM eatwhat_file.file_tombstone WHERE file_id=?", Object.class, seed.fileId()));
        jdbc.update("INSERT INTO eatwhat_file.file_reference_event(logical_key, ref_kind, ref_id, action, reason) VALUES (?, 'food', 'r1', 'RESTORE', 'ADMIN')", seed.logicalKey());
        assertNull(jdbc.queryForObject("SELECT unreferenced_since FROM eatwhat_file.file_tombstone WHERE file_id=?", Object.class, seed.fileId()), "revived");
        jdbc.update("INSERT INTO eatwhat_file.file_reference_event(logical_key, ref_kind, ref_id, action, reason) VALUES (?, 'food', 'r1', 'RELEASE', 'ENTITY_DELETED')", seed.logicalKey());
        assertNotNull(jdbc.queryForObject("SELECT unreferenced_since FROM eatwhat_file.file_tombstone WHERE file_id=?", Object.class, seed.fileId()),
                "V32 sets unreferenced_since again when a revived revision is released");
        assertNotNull(jdbc.queryForObject("SELECT revived_at FROM eatwhat_file.file_tombstone WHERE file_id=?", Object.class, seed.fileId()));

        ageTombstone(seed);
        assertEquals(1, service.detect().unreferencedRevisions(), "a revived-then-released revision is a GC candidate");
        assertEquals(1, service.run().deleted());
        assertFalse(exists(seed.finalKey()));
    }

    @Test
    void notLocalOriginBlocks() throws Exception {
        Seed seed = seedUnreferenced(false, "PRIVATE"); // no upload_intent: the revision was not created by this site
        service.detect();

        assertSurvives(seed, service.run(), "BLOCKED", "NOT_LOCAL_ORIGIN");
    }

    @Test
    void localReplicaWithAnotherStateBlocks() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        jdbc.update("UPDATE eatwhat_file.file_replica SET state='FAILED' WHERE file_id=? AND site='LOCAL'", seed.fileId());
        service.detect();

        assertSurvives(seed, service.run(), "BLOCKED", "LOCAL_REPLICA_STATE");
    }

    @Test
    void blockedCandidatesAreReEvaluatedAndCanBecomeEligibleLater() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        UUID job = UUID.randomUUID();
        jdbc.update("INSERT INTO eatwhat_file.file_transfer_job(job_id, file_id, revision, target_site, kind, status) VALUES (?,?,1,'PEER','COPY_TO_PEER','READY')", job, seed.fileId());
        service.detect();
        assertSurvives(seed, service.run(), "BLOCKED", "TRANSFER_PENDING");

        jdbc.update("UPDATE eatwhat_file.file_transfer_job SET status='DONE', completed_at=clock_timestamp() WHERE job_id=?", job);
        RunResult second = service.run();

        assertEquals(1, second.deleted());
        assertFalse(exists(seed.finalKey()));
    }

    // =====================================================================================================
    // (c) ORPHAN_CANDIDATE
    // =====================================================================================================

    @Test
    void orphanPastWindowIsDeletedAndMarkedCleaned() throws Exception {
        Orphan orphan = seedOrphan("ORPHANED", "CLAIMED", "now() - interval '2 hours'", "now() - interval '1 hour'", "now() - interval '1 hour'", true);
        service.detect();

        service.run();

        assertFalse(exists(orphan.key()));
        assertEquals("CLEANED", orphanState(orphan.key()));
        assertEquals("DELETED", candidate(orphan.key()).get("state"));
        assertEquals(1, verifiedDeletes());
    }

    @Test
    void orphanWhoseObjectIsAlreadyAbsentIsOnlyMarked() throws Exception {
        Orphan orphan = seedOrphan("WRITTEN", "COMPLETE", "now() + interval '1 hour'", null, "now() - interval '1 hour'", false);
        service.detect();

        service.run();

        assertEquals("CLEANED", orphanState(orphan.key()));
        assertEquals("DELETED", candidate(orphan.key()).get("state"));
        assertEquals(0, store.deletes.get(), "nothing to delete: only the rows are updated");
    }

    @Test
    void orphanBackedByARevisionIsKeptAndNeverDeleted() throws Exception {
        Orphan orphan = seedOrphan("ORPHANED", "COMPLETE", "now() + interval '1 hour'", null, "now() - interval '1 hour'", true);
        // Ambiguous-commit case: the revision exists although the orphan row says ORPHANED.
        UUID fileId = UUID.randomUUID();
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key) VALUES (?,?, 'foods', ?)", fileId, UUID.randomUUID(), "foods/" + fileId);
        jdbc.update("INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at) VALUES (?,1,?,?,10,?,'VERIFIED', clock_timestamp())",
                fileId, orphan.key(), SHA, CONTENT_TYPE);
        service.detect();

        service.run();

        assertTrue(exists(orphan.key()));
        assertEquals("KEPT", candidate(orphan.key()).get("state"));
        assertEquals("REVISION_EXISTS", candidate(orphan.key()).get("block_reason"));
        assertEquals("ORPHANED", orphanState(orphan.key()));
        assertEquals(0, store.deletes.get());
    }

    @Test
    void orphanWindowNotElapsedIsBlocked() throws Exception {
        Orphan orphan = seedOrphan("WRITTEN", "COMPLETE", "now() + interval '1 hour'", null, "now() - interval '10 seconds'", true);
        service.detect();
        props.setSafetyWindow(Duration.ofHours(1));

        service.run();

        assertTrue(exists(orphan.key()));
        assertEquals("BLOCKED", candidate(orphan.key()).get("state"));
        assertEquals("WINDOW_NOT_ELAPSED", candidate(orphan.key()).get("block_reason"));
        assertEquals("WRITTEN", orphanState(orphan.key()));
    }

    @Test
    void orphanWithALiveIntentLeaseIsBlocked() throws Exception {
        Orphan orphan = seedOrphan("WRITTEN", "CLAIMED", "now() + interval '1 hour'", "now() + interval '1 hour'", "now() - interval '1 hour'", true);
        service.detect();

        service.run();

        assertTrue(exists(orphan.key()));
        assertEquals("BLOCKED", candidate(orphan.key()).get("state"));
        assertEquals("INTENT_LEASE_LIVE", candidate(orphan.key()).get("block_reason"));
        assertEquals("WRITTEN", orphanState(orphan.key()));
    }

    @Test
    void orphanOfAnActiveUnexpiredIntentIsBlocked() throws Exception {
        Orphan orphan = seedOrphan("WRITTEN", "PENDING", "now() + interval '1 hour'", null, "now() - interval '1 hour'", true);
        service.detect();

        service.run();

        assertTrue(exists(orphan.key()));
        assertEquals("BLOCKED", candidate(orphan.key()).get("state"));
        assertEquals("INTENT_ACTIVE", candidate(orphan.key()).get("block_reason"));
    }

    @Test
    void canonicalOrphanIsKept() throws Exception {
        Orphan orphan = seedOrphan("WRITTEN", "COMPLETE", "now() + interval '1 hour'", null, "now() - interval '1 hour'", true);
        service.detect();
        jdbc.update("UPDATE eatwhat_file.file_orphan_candidate SET state='CANONICAL' WHERE object_key=?", orphan.key());

        service.run();

        assertTrue(exists(orphan.key()));
        assertEquals("KEPT", candidate(orphan.key()).get("state"));
    }

    // =====================================================================================================
    // (d) STAGING
    // =====================================================================================================

    @Test
    void expiredStagingObjectIsDeletedAndTheIntentRowIsUntouched() throws Exception {
        Staging staging = seedStaging("PENDING", "now() - interval '2 hours'", null, "staging/" + uuid(), true);
        String before = intentJson(staging.intentId());
        service.detect();

        service.run();

        assertFalse(exists(staging.key()));
        assertEquals("DELETED", candidate(staging.key()).get("state"));
        assertEquals(before, intentJson(staging.intentId()), "the upload_intent row must not be modified");
    }

    @Test
    void stagingWithALiveClaimLeaseIsBlocked() throws Exception {
        Staging staging = seedStaging("CLAIMED", "now() - interval '2 hours'", "now() + interval '1 hour'", "staging/" + uuid(), true);
        service.detect();

        service.run();

        assertTrue(exists(staging.key()));
        assertEquals("BLOCKED", candidate(staging.key()).get("state"));
        assertEquals("INTENT_LEASE_LIVE", candidate(staging.key()).get("block_reason"));
    }

    @Test
    void stagingNotYetPastTheWindowIsBlocked() throws Exception {
        Staging staging = seedStaging("PENDING", "now() - interval '5 seconds'", null, "staging/" + uuid(), true);
        service.detect();
        assertEquals("DETECTED", candidate(staging.key()).get("state"));
        props.setSafetyWindow(Duration.ofHours(1));

        service.run();

        assertTrue(exists(staging.key()));
        assertEquals("BLOCKED", candidate(staging.key()).get("state"));
        assertEquals("WINDOW_NOT_ELAPSED", candidate(staging.key()).get("block_reason"));
    }

    @Test
    void stagingNotYetExpiredIsNeverEvenDetected() throws Exception {
        Staging staging = seedStaging("PENDING", "now() + interval '1 hour'", null, "staging/" + uuid(), true);

        service.detect();

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_gc_candidate WHERE object_key=?", Integer.class, staging.key()));
        assertTrue(exists(staging.key()));
    }

    @Test
    void keyOutsideTheManagedPrefixIsBlockedAndNeverDeleted() throws Exception {
        Staging staging = seedStaging("PENDING", "now() - interval '2 hours'", null, "uploads/legacy-" + uuid() + ".png", true);
        service.detect();

        RunResult result = service.run();

        assertTrue(exists(staging.key()), "legacy/unmanaged keys are never touched");
        assertEquals("BLOCKED", candidate(staging.key()).get("state"));
        assertEquals("UNMANAGED_PREFIX", candidate(staging.key()).get("block_reason"));
        assertEquals(0, result.deleted());
        assertEquals(0, store.deletes.get());
    }

    @Test
    void stagingKeyThatIsAlsoAFinalKeyIsBlocked() throws Exception {
        String shared = "staging/" + uuid();
        Staging staging = seedStaging("PENDING", "now() - interval '2 hours'", null, shared, true);
        UUID fileId = UUID.randomUUID();
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key) VALUES (?,?, 'foods', ?)", fileId, UUID.randomUUID(), "foods/" + fileId);
        jdbc.update("INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at) VALUES (?,1,?,?,10,?,'VERIFIED', clock_timestamp())",
                fileId, shared, SHA, CONTENT_TYPE);
        service.detect();

        service.run();

        assertTrue(exists(staging.key()));
        assertEquals("BLOCKED", candidate(staging.key()).get("state"));
        assertEquals("KEY_IS_REVISION", candidate(staging.key()).get("block_reason"));
    }

    @Test
    void blankKeyAndFolderMarkersAreRefused() throws Exception {
        // A folder marker ("verified/") and a path-traversal key must never reach the store.
        String marker = "verified/";
        String traversal = "verified/../staging/x";
        for (String bad : List.of(marker, traversal)) {
            seedIntent(insertUser(), UUID.randomUUID(), "foods/" + uuid(), bad, "PENDING", "now() - interval '2 hours'", null);
        }
        service.detect();

        service.run();

        assertEquals(0, store.deletes.get());
        for (String bad : List.of(marker, traversal)) {
            assertEquals("BLOCKED", candidate(bad).get("state"));
        }
    }

    // =====================================================================================================
    // (e) DRY_RUN
    // =====================================================================================================

    @Test
    void dryRunDeletesNothingMutatesNoBusinessRowAndRecordsEligible() throws Exception {
        props.setDryRun(true);
        Seed unreferenced = seedUnreferenced(true, "PRIVATE");
        Orphan orphan = seedOrphan("ORPHANED", "COMPLETE", "now() + interval '1 hour'", null, "now() - interval '1 hour'", true);
        Staging staging = seedStaging("PENDING", "now() - interval '2 hours'", null, "staging/" + uuid(), true);
        Seed blocked = seedUnreferenced(true, "PUBLIC"); // CDN rule: BLOCKED, still reported
        service.detect();
        Map<String, String> before = snapshotBusinessTables();
        store.resetCounters();

        RunResult result = service.run();

        assertEquals(Mode.DRY_RUN, result.mode());
        assertEquals(0, result.deleted());
        assertEquals(0, store.totalCalls.get(), "dry run must not call the object store at all");
        assertEquals(0, store.deletes.get());
        assertEquals(before, snapshotBusinessTables(), "no replica/orphan/intent/tombstone/revision/reference row may change");
        assertTrue(exists(unreferenced.finalKey()) && exists(orphan.key()) && exists(staging.key()) && exists(blocked.finalKey()));
        assertEquals("ELIGIBLE", candidate(unreferenced.finalKey()).get("state"));
        assertEquals("ELIGIBLE", candidate(orphan.key()).get("state"));
        assertEquals("ELIGIBLE", candidate(staging.key()).get("state"));
        assertEquals("BLOCKED", candidate(blocked.finalKey()).get("state"));
        assertEquals(3, result.eligible());
        assertEquals("AVAILABLE_LOCAL", replicaState(unreferenced.fileId()));
        Map<String, Object> run = jdbc.queryForMap("SELECT * FROM eatwhat_file.file_gc_run WHERE run_id=?", result.runId());
        assertEquals("DRY_RUN", run.get("mode"));
        assertEquals(3L, run.get("finding_count"), "finding_count is the eligible count in DRY_RUN");

        // Opening the gates later turns the recorded ELIGIBLE rows into deletions on the next run.
        props.setDryRun(false);
        RunResult executed = service.run();
        assertEquals(3, executed.deleted());
        assertFalse(exists(unreferenced.finalKey()) || exists(orphan.key()) || exists(staging.key()));
        assertTrue(exists(blocked.finalKey()));
    }

    // =====================================================================================================
    // (f) every execute gate is re-checked at runtime
    // =====================================================================================================

    @Test
    void executeIsRefusedWhenAnyGateIsClosedAtRuntime() throws Exception {
        List<Seed> seeds = new ArrayList<>();
        record Gate(String name, Runnable close, Runnable reopen) { }
        List<Gate> gates = List.of(
                new Gate("executeEnabled=false", () -> props.setExecuteEnabled(false), () -> props.setExecuteEnabled(true)),
                new Gate("dryRun=true", () -> props.setDryRun(true), () -> props.setDryRun(false)),
                new Gate("class not enabled", () -> props.setExecuteClasses(new java.util.LinkedHashSet<>(Set.of("STAGING"))),
                        () -> props.setExecuteClasses(new java.util.LinkedHashSet<>(FileGcProperties.EXECUTE_CLASSES))),
                new Gate("blank ack", () -> props.setExecuteAck("  "), () -> props.setExecuteAck(ACK)),
                new Gate("null ack", () -> props.setExecuteAck(null), () -> props.setExecuteAck(ACK)),
                new Gate("site EXTERNAL", () -> site.setMode(SiteProperties.Mode.EXTERNAL), () -> site.setMode(SiteProperties.Mode.CAMPUS)),
                new Gate("site LEGACY", () -> site.setMode(SiteProperties.Mode.LEGACY), () -> site.setMode(SiteProperties.Mode.CAMPUS)),
                new Gate("no classes", () -> props.setExecuteClasses(new java.util.LinkedHashSet<>()),
                        () -> props.setExecuteClasses(new java.util.LinkedHashSet<>(FileGcProperties.EXECUTE_CLASSES))),
                new Gate("inventory disabled", () -> props.setInventoryEnabled(false), () -> props.setInventoryEnabled(true)),
                new Gate("gc disabled", () -> props.setEnabled(false), () -> props.setEnabled(true)),
                new Gate("unresolved ack placeholder", () -> props.setExecuteAck("${DRILL_ID}"), () -> props.setExecuteAck(ACK)));
        for (Gate gate : gates) {
            Seed seed = seedUnreferenced(true, "PRIVATE");
            seeds.add(seed);
            service.detect();
            gate.close().run();
            store.resetCounters();

            RunResult result = service.run();

            assertEquals(0, result.deleted(), gate.name());
            assertEquals(0, store.deletes.get(), gate.name());
            assertTrue(exists(seed.finalKey()), gate.name() + ": object must survive");
            assertEquals("AVAILABLE_LOCAL", replicaState(seed.fileId()), gate.name());
            assertEquals("ELIGIBLE", candidate(seed.finalKey()).get("state"), gate.name());
            gate.reopen().run();
        }
        // Control: with every gate open the same candidates are deleted, so the gates were the only thing in the way.
        RunResult open = service.run();
        assertEquals(seeds.size(), open.deleted());
        for (Seed seed : seeds) {
            assertFalse(exists(seed.finalKey()));
            assertEquals("DELETED", replicaState(seed.fileId()));
        }
    }

    @Test
    void storeWithoutDeleteCapabilityRefusesExecute() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        FileGcService readOnly = new FileGcService(jdbc, tx, props, site, () -> new ReadOnlyStore(realStore));
        readOnly.detect();

        RunResult result = readOnly.run();

        assertEquals(0, result.deleted());
        assertEquals(Mode.DRY_RUN, result.mode(), "a store without DeletableObjectStore cannot be used to delete");
        assertTrue(exists(seed.finalKey()));
        assertEquals("ELIGIBLE", candidate(seed.finalKey()).get("state"));
    }

    // =====================================================================================================
    // (g) per-run cap
    // =====================================================================================================

    @Test
    void maxDeletesPerRunIsRespected() throws Exception {
        props.setMaxDeletesPerRun(2);
        List<Staging> all = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            all.add(seedStaging("PENDING", "now() - interval '2 hours'", null, "staging/" + uuid(), true));
        }
        service.detect();

        RunResult first = service.run();
        assertEquals(2, first.deleted());
        assertEquals(3, all.stream().filter(s -> exists(s.key())).count());

        assertEquals(2, service.run().deleted());
        assertEquals(1, all.stream().filter(s -> exists(s.key())).count());
        assertEquals(1, service.run().deleted());
        assertEquals(0, all.stream().filter(s -> exists(s.key())).count());
        assertEquals(0, service.run().deleted());
    }

    // =====================================================================================================
    // (h) fencing
    // =====================================================================================================

    @Test
    void staleFencingTokenChangesNothingAndDeletesNothing() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        service.detect();
        UUID id = candidateId(seed.finalKey());

        Lease first = service.claim(id); // autocommit: the lease is persisted
        assertNotNull(first);
        assertEquals(1, first.fencingToken());
        assertNull(service.claim(id), "a live lease cannot be claimed again");
        jdbc.update("UPDATE eatwhat_file.file_gc_candidate SET lease_until=clock_timestamp() - interval '1 second' WHERE candidate_id=?", id);
        Lease second = service.claim(id);
        assertNotNull(second, "an expired lease can be reclaimed");
        assertEquals(2, second.fencingToken());
        Map<String, Object> beforeStale = candidate(seed.finalKey());

        Outcome stale = service.process(first, null, Mode.EXECUTE);

        assertEquals(Kind.STALE, stale.kind());
        assertTrue(exists(seed.finalKey()), "the stale holder must not delete");
        assertEquals(0, store.deletes.get());
        assertEquals("AVAILABLE_LOCAL", replicaState(seed.fileId()));
        assertEquals(beforeStale.get("state"), candidate(seed.finalKey()).get("state"));
        assertEquals(2L, candidate(seed.finalKey()).get("fencing_token"));
        assertEquals("LEASED", candidate(seed.finalKey()).get("state"), "the new holder's lease is untouched");

        // A holder whose own lease expired is stale too, even with the current token.
        jdbc.update("UPDATE eatwhat_file.file_gc_candidate SET lease_until=clock_timestamp() - interval '1 second' WHERE candidate_id=?", id);
        assertEquals(Kind.STALE, service.process(second, null, Mode.EXECUTE).kind());
        assertTrue(exists(seed.finalKey()));
        assertEquals(0, store.deletes.get());

        // A fresh claim then completes normally.
        Lease third = service.claim(id);
        assertNotNull(third);
        assertEquals(Kind.DELETED, service.process(third, null, Mode.EXECUTE).kind());
        assertFalse(exists(seed.finalKey()));
        assertEquals("DELETED", candidate(seed.finalKey()).get("state"));
    }

    // =====================================================================================================
    // (i) lock contention
    // =====================================================================================================

    @Test
    void lockedTombstoneSkipsTheCandidateAndReleasesTheLease() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        service.detect();

        try (Connection other = DriverManager.getConnection(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)) {
            other.setAutoCommit(false);
            try (var statement = other.createStatement()) {
                statement.execute("SELECT 1 FROM eatwhat_file.file_tombstone WHERE file_id='" + seed.fileId() + "' FOR UPDATE");
                RunResult skipped = service.run();

                assertEquals(0, skipped.deleted());
                assertEquals(1, skipped.skipped());
                assertTrue(exists(seed.finalKey()));
                assertEquals(0, store.deletes.get());
                assertEquals("AVAILABLE_LOCAL", replicaState(seed.fileId()));
                Map<String, Object> row = candidate(seed.finalKey());
                assertNotEquals("LEASED", row.get("state"));
                assertNull(row.get("lease_until"), "the lease is released");
                assertEquals("DETECTED", row.get("state"));
            } finally {
                other.rollback();
            }
        }

        assertEquals(1, service.run().deleted(), "once the lock is gone the candidate is processed");
        assertFalse(exists(seed.finalKey()));
    }

    @Test
    void lockedIntentSkipsAnOrphanCandidate() throws Exception {
        Orphan orphan = seedOrphan("ORPHANED", "COMPLETE", "now() + interval '1 hour'", null, "now() - interval '1 hour'", true);
        service.detect();

        try (Connection other = DriverManager.getConnection(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)) {
            other.setAutoCommit(false);
            try (var statement = other.createStatement()) {
                // complete() locks the intent row first; simulate a canonicalize in flight.
                statement.execute("UPDATE eatwhat_file.upload_intent SET updated_at=now() WHERE intent_id='" + orphan.intentId() + "'");
                RunResult skipped = service.run();

                assertTrue(exists(orphan.key()));
                assertEquals(0, store.deletes.get());
                assertTrue(skipped.skipped() >= 1);
                assertEquals("DETECTED", candidate(orphan.key()).get("state"));
                assertNull(candidate(orphan.key()).get("lease_until"));
            } finally {
                other.rollback();
            }
        }
        service.run();
        assertFalse(exists(orphan.key()));
    }

    // =====================================================================================================
    // (j) two-phase delete: DELETING is committed before the object delete, outside every business-row lock
    // =====================================================================================================

    @Test
    void referenceAttemptWhileTheObjectDeleteIsInFlightIsRejectedAtOnceAndNoLockIsHeld() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        service.detect();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        store.deleteEntered = entered;
        store.deleteProceed = proceed;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<RunResult> gc = pool.submit(() -> service.run());
            assertTrue(entered.await(30, TimeUnit.SECONDS), "the GC reached the physical delete");

            // Phase 1 is committed and visible; the object is still there.
            assertEquals("DELETING", replicaState(seed.fileId()));
            assertEquals("DELETING", candidate(seed.finalKey()).get("state"));
            assertTrue(exists(seed.finalKey()));
            // No business-row lock is held across the delete.
            assertEquals(1, jdbc.queryForList("SELECT 1 FROM eatwhat_file.file_tombstone WHERE file_id=? FOR UPDATE NOWAIT", seed.fileId()).size(),
                    "the tombstone must not be locked while the object is being deleted");
            // A reference, and a RESTORE, are rejected immediately (they do not wait for the GC).
            assertThrows(DataAccessException.class, () -> jdbc.update(
                    "INSERT INTO eatwhat_file.file_reference(logical_key, ref_kind, ref_id, visibility) VALUES (?, 'food', 'late', 'PRIVATE')", seed.logicalKey()));
            assertThrows(DataAccessException.class, () -> jdbc.update(
                    "INSERT INTO eatwhat_file.file_reference_event(logical_key, ref_kind, ref_id, action, reason) VALUES (?, 'food', 'r1', 'RESTORE', 'ADMIN')", seed.logicalKey()));

            proceed.countDown();
            assertEquals(1, gc.get(30, TimeUnit.SECONDS).deleted());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_reference WHERE logical_key=? AND ref_id='late'", Integer.class, seed.logicalKey()));
        assertFalse(exists(seed.finalKey()));
        assertEquals("DELETED", replicaState(seed.fileId()));
        assertEquals("DELETED", candidate(seed.finalKey()).get("state"));
    }

    @Test
    void deletingRevisionIsUnreadableEverywhereAndInvisibleToInventory() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        // Give it a live PUBLIC reference and make it readable, then move the replica to DELETING.
        jdbc.update("INSERT INTO eatwhat_file.file_reference(logical_key, ref_kind, ref_id, visibility) VALUES (?, 'food', 'live', 'PUBLIC')", seed.logicalKey());
        FileAliasResolver aliases = new FileAliasResolver(jdbc);
        FileLifecycleService lifecycle = new FileLifecycleService(jdbc, null, null, null, tx);
        you.v50to.eatwhat.config.FileReplicationProperties replication = new you.v50to.eatwhat.config.FileReplicationProperties();
        replication.setCdnOutputEnabled(true);
        you.v50to.eatwhat.service.storage.replication.FileUrlResolver urls = new you.v50to.eatwhat.service.storage.replication.FileUrlResolver(
                jdbc, replication, new you.v50to.eatwhat.service.storage.replication.ReplicationConsentGate() {
                    @Override public boolean replicationAllowed(UUID ownerGid) { return true; }
                    @Override public Long currentEpoch(UUID ownerGid) { return 1L; }
                });
        assertEquals(seed.finalKey(), aliases.resolve(seed.logicalKey()).readableObjectKey(), "control: readable while AVAILABLE_LOCAL");
        assertEquals(seed.finalKey(), lifecycle.resolveAlias(seed.logicalKey()));

        jdbc.update("UPDATE eatwhat_file.file_replica SET state='DELETING' WHERE file_id=? AND site='LOCAL'", seed.fileId());

        assertNull(aliases.resolve(seed.logicalKey()).readableObjectKey());
        assertNull(lifecycle.resolveAlias(seed.logicalKey()));
        var described = urls.describe(seed.logicalKey());
        assertEquals(you.v50to.eatwhat.service.storage.replication.FileUrlResolver.State.PENDING, described.state());
        assertNull(described.finalKey(), "the object key of a DELETING revision is never exposed");
        assertFalse(described.cdnEligible());

        // Inventory: an absent object behind a DELETING replica is expected, behind an AVAILABLE_LOCAL one it is MISSING_OBJECT.
        realStore.deleteObject(seed.finalKey());
        Seed control = seedUnreferenced(true, "PRIVATE");
        realStore.deleteObject(control.finalKey());
        Thread.sleep(2500);
        FileGcProperties inventoryProps = new FileGcProperties();
        inventoryProps.setInventoryEnabled(true);
        inventoryProps.setSafetyWindow(Duration.ofSeconds(1));
        var report = new you.v50to.eatwhat.service.storage.replication.FileInventoryService(jdbc, inventoryProps, () -> realStore, () -> null).runInventory("LOCAL");
        assertEquals(1L, report.findings().getOrDefault("MISSING_OBJECT", 0L), "only the AVAILABLE_LOCAL control is reported: " + report.findings());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_inventory_finding WHERE kind='MISSING_OBJECT' AND object_key=?", Integer.class, control.finalKey()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_inventory_finding WHERE object_key=?", Integer.class, seed.finalKey()));
    }

    // =====================================================================================================
    // (k) failures after phase 1 leave the revision DELETING and resumable (never silently reverted)
    // =====================================================================================================

    @Test
    void failedDeleteLeavesTheRevisionDeletingAndTheNextRunResumesIt() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        service.detect();
        store.failDelete = new IllegalStateException("simulated delete outage");

        RunResult result = service.run();

        assertEquals(0, result.deleted());
        assertEquals(1, result.errors());
        assertTrue(exists(seed.finalKey()));
        assertEquals("DELETING", replicaState(seed.fileId()), "phase 1 is committed and not reverted");
        Map<String, Object> row = candidate(seed.finalKey());
        assertEquals("DELETING", row.get("state"));
        assertEquals(1, row.get("attempts"));
        assertTrue(String.valueOf(row.get("last_error")).contains("object delete failed"), String.valueOf(row.get("last_error")));
        assertNotNull(row.get("eligible_after"), "backoff is recorded");
        assertNull(row.get("lease_until"));
        assertNull(row.get("deleted_at"));
        assertEquals(1L, jdbc.queryForObject("SELECT error_count FROM eatwhat_file.file_gc_run WHERE run_id=?", Long.class, result.runId()));
        // While DELETING nothing can reference it, even though the object is physically still there.
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO eatwhat_file.file_reference(logical_key, ref_kind, ref_id, visibility) VALUES (?, 'food', 'again', 'PRIVATE')", seed.logicalKey()));

        store.failDelete = null;
        RunResult retry = service.run();

        assertEquals(1, retry.deleted(), "the resume completes the delete idempotently");
        assertFalse(exists(seed.finalKey()));
        assertEquals("DELETED", replicaState(seed.fileId()));
        assertEquals("DELETED", candidate(seed.finalKey()).get("state"));
    }

    @Test
    void failedDeleteBacksOffExponentiallyWhenAGcIntervalIsConfigured() throws Exception {
        props.setGcInterval(Duration.ofMinutes(10));
        Seed seed = seedUnreferenced(true, "PRIVATE");
        service.detect();
        store.failDelete = new IllegalStateException("simulated delete outage");

        service.run();
        long firstBackoff = jdbc.queryForObject("SELECT extract(epoch FROM (eligible_after - clock_timestamp()))::bigint FROM eatwhat_file.file_gc_candidate WHERE object_key=?", Long.class, seed.finalKey());
        assertTrue(firstBackoff > 500 && firstBackoff <= 600, "first backoff is about one gc interval: " + firstBackoff);

        store.failDelete = null;
        RunResult early = service.run();
        assertEquals(0, early.examined(), "a candidate in backoff is not claimed");
        assertTrue(exists(seed.finalKey()));
        assertEquals("DELETING", replicaState(seed.fileId()));
    }

    @Test
    void deleteThatAppliedButReportedAnErrorKeepsTheDeletion() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        service.detect();
        store.failDelete = new IllegalStateException("response lost after the delete was applied");
        store.applyThenFail = true;

        RunResult result = service.run();

        assertEquals(1, result.deleted(), "HEAD proves the object is gone, so the database must say deleted too");
        assertFalse(exists(seed.finalKey()));
        assertEquals("DELETED", replicaState(seed.fileId()));
        assertEquals("DELETED", candidate(seed.finalKey()).get("state"));
    }

    @Test
    void objectStillPresentAfterDeleteStaysDeletingUntilItIsReallyGone() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        service.detect();
        store.ignoreDeletes = true; // the store acknowledges the delete but the object remains

        RunResult result = service.run();

        assertEquals(0, result.deleted());
        assertEquals(1, result.errors());
        assertTrue(exists(seed.finalKey()));
        assertEquals("DELETING", replicaState(seed.fileId()));
        assertEquals("DELETING", candidate(seed.finalKey()).get("state"));
        assertEquals(1, candidate(seed.finalKey()).get("attempts"));

        store.ignoreDeletes = false;
        assertEquals(1, service.run().deleted());
        assertFalse(exists(seed.finalKey()));
    }

    @Test
    void phaseTwoFailureAfterTheObjectWasDeletedIsResumedWithoutResurrectingAnything() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        service.detect();
        // Fail phase 2 only: moving the replica from DELETING to DELETED.
        jdbc.execute("""
                CREATE FUNCTION public.fail_replica_deleted_for_test() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.state='DELETED' THEN RAISE EXCEPTION 'synthetic phase two failure' USING ERRCODE='P0001'; END IF; RETURN NEW; END $$
                """);
        jdbc.execute("CREATE TRIGGER fail_replica_deleted_for_test BEFORE UPDATE ON eatwhat_file.file_replica "
                + "FOR EACH ROW EXECUTE FUNCTION public.fail_replica_deleted_for_test()");

        RunResult failed = service.run();

        assertEquals(0, failed.deleted());
        assertEquals(1, failed.errors());
        assertFalse(exists(seed.finalKey()), "the object delete already happened");
        assertEquals("DELETING", replicaState(seed.fileId()), "still unreadable and unreferenceable");
        assertEquals("DELETING", candidate(seed.finalKey()).get("state"));
        assertEquals(1, candidate(seed.finalKey()).get("attempts"));

        jdbc.execute("DROP TRIGGER fail_replica_deleted_for_test ON eatwhat_file.file_replica");
        jdbc.execute("DROP FUNCTION public.fail_replica_deleted_for_test()");
        RunResult resumed = service.run();

        assertEquals(1, resumed.deleted());
        assertFalse(exists(seed.finalKey()));
        assertEquals("DELETED", replicaState(seed.fileId()));
        assertEquals("DELETED", candidate(seed.finalKey()).get("state"));
        assertEquals(0, service.run().examined(), "idempotent: nothing left to do");
    }

    @Test
    void resumeBlockedByAPeerReplicaRaisesOneAlertAndStaysDeletingNeverReverting() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        service.detect();
        // Model pre-existing recovery facts. V35 now rejects a NEW peer after phase 1; that admission
        // race is covered by FileCoordinatedGcIT. Existing inconsistent facts still require a safe hold.
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state) VALUES (?,1,'PEER',?, 'PENDING_SYNC')", seed.fileId(), seed.finalKey());
        jdbc.update("UPDATE eatwhat_file.file_replica SET state='DELETING' WHERE file_id=? AND site='LOCAL'",seed.fileId());
        jdbc.update("UPDATE eatwhat_file.file_gc_candidate SET state='DELETING' WHERE file_id=?",seed.fileId());

        RunResult first = service.run();
        service.run();

        assertEquals(0, first.deleted());
        assertTrue(exists(seed.finalKey()), "a blocked resume deletes nothing");
        assertEquals("DELETING", replicaState(seed.fileId()), "never silently reverted");
        Map<String, Object> row = candidate(seed.finalKey());
        assertEquals("DELETING", row.get("state"));
        assertEquals("PEER_ACK_UNAVAILABLE", row.get("block_reason"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_replica_alert WHERE file_id=? AND alert_type='GC_DELETING_BLOCKED'", Integer.class, seed.fileId()),
                "a loud alert, once");
    }

    @Test
    void deletingCandidatesAreOnlyReportedInDryRunAndCompletedWhenExecuteIsBackOn() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        service.detect();
        store.failDelete = new IllegalStateException("simulated delete outage");
        service.run();
        store.failDelete = null;
        props.setDryRun(true);
        store.resetCounters();

        RunResult dry = service.run();

        assertEquals(Mode.DRY_RUN, dry.mode());
        assertEquals(0, dry.deleted());
        assertEquals(0, store.totalCalls.get(), "no store call in dry run, even for a DELETING candidate");
        assertEquals("DELETING", candidate(seed.finalKey()).get("state"));
        assertTrue(exists(seed.finalKey()));

        props.setDryRun(false);
        assertEquals(1, service.run().deleted());
        assertFalse(exists(seed.finalKey()));
    }

    @Test
    void headFailureBeforeAnOrphanDeleteRollsBackEverything() throws Exception {
        Orphan orphan = seedOrphan("ORPHANED", "COMPLETE", "now() + interval '1 hour'", null, "now() - interval '1 hour'", true);
        service.detect();
        store.failHead = new IllegalStateException("simulated head outage");

        RunResult result = service.run();

        assertTrue(result.errors() >= 1);
        assertTrue(exists(orphan.key()));
        assertEquals("ORPHANED", orphanState(orphan.key()), "the orphan row rolled back");
        assertEquals(0, store.deletes.get());
    }

    // =====================================================================================================
    // review follow-ups: WRITING orphans, ack rule, interrupts, lock timeout, replication interplay
    // =====================================================================================================

    @Test
    void writingOrphanWithAnAbsentObjectIsBlockedNotCleanedUntilTheObjectLands() throws Exception {
        Orphan orphan = seedOrphan("WRITING", "COMPLETE", "now() + interval '1 hour'", null, "now() - interval '1 hour'", false);
        service.detect();

        RunResult first = service.run();

        assertEquals(0, first.deleted());
        assertEquals("BLOCKED", candidate(orphan.key()).get("state"));
        assertEquals("WRITE_IN_FLIGHT", candidate(orphan.key()).get("block_reason"));
        assertEquals("WRITING", orphanState(orphan.key()), "an in-flight write must not be marked CLEANED");
        assertEquals(0, store.deletes.get());

        // The PUT lands; the row is still WRITING past the window: now it is a real orphan and is deleted.
        put(realStore, orphan.key(), 6);
        RunResult second = service.run();

        assertEquals(1, second.deleted());
        assertFalse(exists(orphan.key()));
        assertEquals("CLEANED", orphanState(orphan.key()));
        assertEquals("DELETED", candidate(orphan.key()).get("state"));
    }

    @Test
    void writtenOrphanWithAnAbsentObjectIsStillJustMarked() throws Exception {
        Orphan orphan = seedOrphan("WRITTEN", "COMPLETE", "now() + interval '1 hour'", null, "now() - interval '1 hour'", false);
        service.detect();

        service.run();

        assertEquals("CLEANED", orphanState(orphan.key()));
        assertEquals("DELETED", candidate(orphan.key()).get("state"));
    }

    @Test
    void unresolvedAckPlaceholderRefusesExecutionAtRuntime() throws Exception {
        props.setExecuteAck("${DRILL_ID}");
        Seed seed = seedUnreferenced(true, "PRIVATE");
        service.detect();

        RunResult result = service.run();

        assertEquals(Mode.DRY_RUN, result.mode());
        assertEquals(0, result.deleted());
        assertTrue(exists(seed.finalKey()));
        assertEquals("false", jdbc.queryForObject("SELECT config_snapshot->>'ackPresent' FROM eatwhat_file.file_gc_run WHERE run_id=?", String.class, result.runId()));
        assertFalse(FileGcProperties.isAckUsable("${DRILL_ID}"));
        assertFalse(FileGcProperties.isAckUsable("  "));
        assertFalse(FileGcProperties.isAckUsable(null));
        assertTrue(FileGcProperties.isAckUsable("drill-1"));
    }

    @Test
    void interruptedDeleteThatWasAppliedKeepsTheDeletionAndRestoresTheInterruptFlag() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        service.detect();
        store.abortLikeInterrupt = true;
        store.applyThenFail = true;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Object[]> run = pool.submit(() -> {
                RunResult result = service.run();
                return new Object[]{result, Thread.currentThread().isInterrupted()};
            });
            Object[] outcome = run.get(60, TimeUnit.SECONDS);
            assertEquals(1, ((RunResult) outcome[0]).deleted(), "the object is absent, so the database must say deleted as well");
            assertEquals(Boolean.TRUE, outcome[1], "the interrupt must not be swallowed");
        } finally {
            pool.shutdownNow();
        }
        assertFalse(exists(seed.finalKey()));
        assertEquals("DELETED", replicaState(seed.fileId()));
        assertEquals("DELETED", candidate(seed.finalKey()).get("state"));
    }

    @Test
    void interruptedDeleteThatWasNotAppliedLeavesTheRevisionDeleting() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        service.detect();
        store.abortLikeInterrupt = true;
        store.applyThenFail = false;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            RunResult result = pool.submit(() -> service.run()).get(60, TimeUnit.SECONDS);
            assertEquals(0, result.deleted());
            assertEquals(1, result.errors());
        } finally {
            pool.shutdownNow();
        }
        assertTrue(exists(seed.finalKey()));
        assertEquals("DELETING", replicaState(seed.fileId()), "phase 1 stays committed; the resume finishes it");
        assertEquals("DELETING", candidate(seed.finalKey()).get("state"));
        assertEquals(1, candidate(seed.finalKey()).get("attempts"));
    }

    @Test
    void lockWaitTimeoutOnTheReplicaRowSkipsTheCandidateWithoutCountingAnAttempt() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        service.detect();

        try (Connection other = DriverManager.getConnection(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)) {
            other.setAutoCommit(false);
            try (var statement = other.createStatement()) {
                statement.execute("SELECT 1 FROM eatwhat_file.file_replica WHERE file_id='" + seed.fileId() + "' AND site='LOCAL' FOR UPDATE");
                long started = System.nanoTime();
                RunResult skipped = service.run();
                long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);

                assertTrue(elapsedSeconds >= 4 && elapsedSeconds < 30, "bounded wait (about 5s lock_timeout): " + elapsedSeconds);
                assertEquals(0, skipped.deleted());
                assertEquals(1, skipped.skipped());
                assertEquals(0, skipped.errors());
                assertTrue(exists(seed.finalKey()));
                assertEquals(0, store.deletes.get());
                Map<String, Object> row = candidate(seed.finalKey());
                assertEquals("DETECTED", row.get("state"));
                assertNull(row.get("lease_until"));
                assertEquals(0, row.get("attempts"));
            } finally {
                other.rollback();
            }
        }
        assertEquals(1, service.run().deleted());
        assertFalse(exists(seed.finalKey()));
    }

    @Test
    void enqueueMissingSkipsPhysicallyDeletedRevisionsAndStillEnqueuesLiveOnes() throws Exception {
        Seed deleted = seedUnreferenced(true, "PRIVATE");
        Seed live = seedUnreferenced(true, "PRIVATE");
        jdbc.update("UPDATE eatwhat_file.file_tombstone SET unreferenced_since=NULL WHERE file_id=?", live.fileId());
        service.detect();
        assertEquals(1, service.run().deleted()); // only 'deleted' is still unreferenced past retention

        var enqueuer = new you.v50to.eatwhat.service.storage.replication.FileReplicationEnqueuer(jdbc,
                new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                        .getBeanProvider(you.v50to.eatwhat.account.ConsentStore.class));
        assertEquals(1, enqueuer.enqueueMissing(10), "only the revision whose local object still exists");

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_replica WHERE file_id=? AND site='PEER'", Integer.class, deleted.fileId()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_transfer_job WHERE file_id=?", Integer.class, deleted.fileId()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_transfer_job WHERE file_id=?", Integer.class, live.fileId()));
    }

    @Test
    void enqueueMissingSkipsARevisionWhoseDeleteIsInFlight() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        service.detect();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        store.deleteEntered = entered;
        store.deleteProceed = proceed;
        var enqueuer = new you.v50to.eatwhat.service.storage.replication.FileReplicationEnqueuer(jdbc,
                new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                        .getBeanProvider(you.v50to.eatwhat.account.ConsentStore.class));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<RunResult> gc = pool.submit(() -> service.run());
            assertTrue(entered.await(30, TimeUnit.SECONDS));
            assertEquals("DELETING", replicaState(seed.fileId()));

            assertEquals(0, enqueuer.enqueueMissing(10), "a DELETING revision has no bytes to replicate (and nothing blocks on the GC)");

            proceed.countDown();
            assertEquals(1, gc.get(30, TimeUnit.SECONDS).deleted());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_transfer_job WHERE file_id=?", Integer.class, seed.fileId()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_replica WHERE file_id=? AND site='PEER'", Integer.class, seed.fileId()));
    }

    // =====================================================================================================
    // (l) idempotency
    // =====================================================================================================

    @Test
    void secondRunAfterSuccessChangesNothing() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        Orphan orphan = seedOrphan("ORPHANED", "COMPLETE", "now() + interval '1 hour'", null, "now() - interval '1 hour'", true);
        service.detect();
        assertEquals(2, service.run().deleted()); // the revision and the orphan
        Map<String, String> tables = snapshotBusinessTables();
        String candidates = candidateSnapshot();
        int deletes = store.deletes.get();

        assertEquals(0, service.detect().total(), "detect must not resurrect DELETED rows");
        RunResult again = service.run();

        assertEquals(0, again.examined());
        assertEquals(0, again.deleted());
        assertEquals(deletes, store.deletes.get());
        assertEquals(tables, snapshotBusinessTables());
        assertEquals(candidates, candidateSnapshot());
        assertEquals("DELETED", candidate(seed.finalKey()).get("state"));
        assertEquals("DELETED", candidate(orphan.key()).get("state"));
    }

    // =====================================================================================================
    // (m) T09 end to end with the real lifecycle service
    // =====================================================================================================

    @Test
    void t09DatabaseFailureAfterTheFinalPutLeavesAnOrphanThatTheGcRemovesAndNoUrlWasEverReturned() throws Exception {
        long owner = insertUser();
        BucketBackedStorage objects = new BucketBackedStorage(realStore);
        FileLifecycleService lifecycleService = lifecycleService(objects, 4, 3);
        FileAliasResolver resolver = new FileAliasResolver(jdbc);

        var upload = lifecycleService.presign(owner, UploadBiz.FOODS, "t09.png", "image/png", (long) PNG.length);
        String stagingKey = jdbc.queryForObject("SELECT staging_key FROM eatwhat_file.upload_intent WHERE logical_key=?", String.class, upload.key());
        objects.stage(stagingKey, PNG);
        jdbc.execute("""
                CREATE FUNCTION public.fail_file_revision_insert_for_test() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Synthetic file metadata commit failure' USING ERRCODE='P0001'; END $$
                """);
        jdbc.execute("CREATE TRIGGER fail_file_revision_insert_for_test BEFORE INSERT ON eatwhat_file.file_revision "
                + "FOR EACH ROW EXECUTE FUNCTION public.fail_file_revision_insert_for_test()");

        assertThrows(DataAccessException.class, () -> lifecycleService.complete(owner, upload.key()));

        jdbc.execute("DROP TRIGGER fail_file_revision_insert_for_test ON eatwhat_file.file_revision");
        jdbc.execute("DROP FUNCTION public.fail_file_revision_insert_for_test()");
        String orphanKey = jdbc.queryForObject("SELECT object_key FROM eatwhat_file.file_orphan_candidate WHERE state='ORPHANED'", String.class);
        assertTrue(exists(orphanKey), "the final object was PUT before the database step failed");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_key_alias WHERE logical_key=?", Integer.class, upload.key()));
        assertNull(lifecycleService.resolveAlias(upload.key()), "no URL before the GC");
        assertNull(resolver.resolve(upload.key()).readableObjectKey());

        // Too early: the intent lease and expiry (plus the safety window) have not passed. Nothing may be deleted.
        service.detect();
        service.run();
        assertTrue(exists(orphanKey), "an orphan of a live intent is never deleted early");

        Thread.sleep(6500); // intent expiry (4s) + safety window (1s) + margin; lease (3s) has expired
        service.detect();
        RunResult result = service.run();

        assertTrue(result.deleted() >= 1, "orphan removed: " + result);
        assertFalse(exists(orphanKey), "the orphaned final object is gone");
        assertEquals("CLEANED", orphanState(orphanKey));
        assertEquals("DELETED", candidate(orphanKey).get("state"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_revision", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_key_alias WHERE logical_key=?", Integer.class, upload.key()));
        assertNull(lifecycleService.resolveAlias(upload.key()), "the resolver never returned a URL");
        assertNull(resolver.resolve(upload.key()).readableObjectKey());
    }

    @Test
    void gcCleaningAnOrphanWhoseFinalPutIsInFlightMakesCompleteFailInsteadOfResurrectingIt() throws Exception {
        // Worst case the safety window normally prevents: the GC considers a WRITING orphan eligible while complete()
        // is still between the final PUT and the WRITTEN mark. The guarded WRITING->WRITTEN update must then fail.
        long owner = insertUser();
        BucketBackedStorage objects = new BucketBackedStorage(realStore);
        FileLifecycleService lifecycleService = lifecycleService(objects, 900, 120);
        var upload = lifecycleService.presign(owner, UploadBiz.FOODS, "race.png", "image/png", (long) PNG.length);
        objects.stage(jdbc.queryForObject("SELECT staging_key FROM eatwhat_file.upload_intent WHERE logical_key=?", String.class, upload.key()), PNG);
        CountDownLatch put = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        objects.afterPut = () -> {
            put.countDown();
            try {
                resume.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        String key;
        try {
            Future<?> complete = pool.submit(() -> lifecycleService.complete(owner, upload.key()));
            assertTrue(put.await(30, TimeUnit.SECONDS));
            key = jdbc.queryForObject("SELECT object_key FROM eatwhat_file.file_orphan_candidate WHERE state='WRITING'", String.class);
            assertTrue(exists(key), "the final object has been PUT");
            jdbc.update("UPDATE eatwhat_file.file_orphan_candidate SET updated_at=now() - interval '1 hour' WHERE object_key=?", key);
            jdbc.update("UPDATE eatwhat_file.upload_intent SET status='REJECTED', lease_until=NULL WHERE logical_key=?", upload.key());
            props.setExecuteClasses(new java.util.LinkedHashSet<>(Set.of("ORPHAN_CANDIDATE")));
            service.detect();
            assertEquals(1, service.run().deleted());
            assertFalse(exists(key));

            resume.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> complete.get(30, TimeUnit.SECONDS));
            assertInstanceOf(BizException.class, failure.getCause());
        } finally {
            resume.countDown();
            pool.shutdownNow();
        }
        assertEquals("CLEANED", orphanState(key), "the cleaned orphan is not resurrected");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_revision", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_key_alias WHERE logical_key=?", Integer.class, upload.key()));
    }

    @Test
    void canonicalizeRollsBackWhenTheOrphanWasCleanedWhileItWasBlockedOnTheIntentLock() throws Exception {
        // canonicalize inserts the revision before it updates the orphan row, and the GC cannot see that uncommitted
        // revision. The final "state='WRITTEN'" guard is what turns "GC cleaned the orphan first" into a rollback.
        long owner = insertUser();
        BucketBackedStorage objects = new BucketBackedStorage(realStore);
        FileLifecycleService lifecycleService = lifecycleService(objects, 900, 120);
        var upload = lifecycleService.presign(owner, UploadBiz.FOODS, "race2.png", "image/png", (long) PNG.length);
        objects.stage(jdbc.queryForObject("SELECT staging_key FROM eatwhat_file.upload_intent WHERE logical_key=?", String.class, upload.key()), PNG);
        CountDownLatch put = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        objects.afterPut = () -> {
            put.countDown();
            try {
                resume.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = DriverManager.getConnection(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)) {
            Future<?> complete = pool.submit(() -> lifecycleService.complete(owner, upload.key()));
            assertTrue(put.await(30, TimeUnit.SECONDS));
            holder.setAutoCommit(false);
            try (var statement = holder.createStatement()) {
                // Hold the intent row so that canonicalize's first statement (the intent update) blocks.
                statement.execute("SELECT 1 FROM eatwhat_file.upload_intent WHERE logical_key='" + upload.key() + "' FOR UPDATE");
                resume.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' "
                        + "AND query LIKE 'UPDATE eatwhat_file.upload_intent SET status=''COMPLETE''%'", Integer.class) == 0) {
                    assertTrue(System.nanoTime() < deadline, "canonicalize never blocked on the intent lock");
                    Thread.sleep(50);
                }
                // What the GC would have done, had it won the lock: the orphan (state WRITTEN by now) is cleaned.
                assertEquals(1, statement.executeUpdate("UPDATE eatwhat_file.file_orphan_candidate SET state='CLEANED' WHERE state='WRITTEN'"));
                holder.commit();
            }
            ExecutionException failure = assertThrows(ExecutionException.class, () -> complete.get(30, TimeUnit.SECONDS));
            assertInstanceOf(BizException.class, failure.getCause());
        } finally {
            resume.countDown();
            pool.shutdownNow();
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_revision", Integer.class),
                "canonicalize rolled back: no revision for the (to be deleted) object");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_key_alias WHERE logical_key=?", Integer.class, upload.key()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_replica", Integer.class));
        assertEquals("CLEANED", jdbc.queryForObject("SELECT state FROM eatwhat_file.file_orphan_candidate", String.class));
    }

    private FileLifecycleService lifecycleService(FileObjectStorage objects, long intentTtlSeconds, long claimLeaseSeconds) {
        S3StorageProperties storage = new S3StorageProperties();
        storage.setMaxFileSizeBytes(1024);
        storage.setAllowedContentTypes(List.of("image/png", "image/jpeg"));
        FileLifecycleProperties lifecycle = new FileLifecycleProperties();
        lifecycle.setLifecycleEnabled(true);
        lifecycle.setIntentTtlSeconds(intentTtlSeconds);
        lifecycle.setClaimLeaseSeconds(claimLeaseSeconds);
        return new FileLifecycleService(jdbc, objects, storage, lifecycle, tx);
    }

    // =====================================================================================================
    // (n) V31 schema
    // =====================================================================================================

    @Test
    void v31ReplicaStateAcceptsDeletedOnBothSitesAndStillRejectsUnknownStates() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state) VALUES (?,1,'PEER',?, 'PENDING_SYNC')", seed.fileId(), seed.finalKey());

        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE eatwhat_file.file_replica SET state='BOGUS' WHERE file_id=?", seed.fileId()));
        // Every V28 value stays valid (the replica is not DELETING/DELETED yet, so it may move freely).
        for (String state : List.of("PENDING_SYNC", "QUARANTINED", "PAUSED_CONSENT", "R2_AVAILABLE", "FAILED", "AVAILABLE_LOCAL")) {
            assertEquals(1, jdbc.update("UPDATE eatwhat_file.file_replica SET state=? WHERE file_id=? AND site='LOCAL'", state, seed.fileId()), state);
        }
        // The V28 site/state rule is unchanged.
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE eatwhat_file.file_replica SET state='PEER_VERIFIED' WHERE file_id=? AND site='LOCAL'", seed.fileId()));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE eatwhat_file.file_replica SET state='AVAILABLE_LOCAL' WHERE file_id=? AND site='PEER'", seed.fileId()));
        // Identity stays immutable.
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE eatwhat_file.file_replica SET object_key='verified/other' WHERE file_id=? AND site='LOCAL'", seed.fileId()));
        // DELETING and DELETED are accepted on both sites.
        assertEquals(1, jdbc.update("UPDATE eatwhat_file.file_replica SET state='DELETING' WHERE file_id=? AND site='LOCAL'", seed.fileId()));
        assertEquals(1, jdbc.update("UPDATE eatwhat_file.file_replica SET state='DELETED' WHERE file_id=? AND site='PEER'", seed.fileId()));
    }

    @Test
    void replicaDeleteStatesAreOneWay() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        assertEquals(1, jdbc.update("UPDATE eatwhat_file.file_replica SET state='DELETING' WHERE file_id=? AND site='LOCAL'", seed.fileId()));

        // DELETING may only become DELETED (or stay DELETING).
        for (String state : List.of("AVAILABLE_LOCAL", "PENDING_SYNC", "QUARANTINED", "PAUSED_CONSENT", "R2_AVAILABLE", "FAILED")) {
            assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE eatwhat_file.file_replica SET state=? WHERE file_id=? AND site='LOCAL'", state, seed.fileId()), state);
        }
        assertEquals(1, jdbc.update("UPDATE eatwhat_file.file_replica SET state='DELETING', updated_at=clock_timestamp() WHERE file_id=? AND site='LOCAL'", seed.fileId()));
        assertEquals(1, jdbc.update("UPDATE eatwhat_file.file_replica SET state='DELETED' WHERE file_id=? AND site='LOCAL'", seed.fileId()));
        // DELETED is terminal; a same-state update (for example updated_at) is not a transition.
        for (String state : List.of("AVAILABLE_LOCAL", "DELETING", "QUARANTINED", "FAILED")) {
            assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE eatwhat_file.file_replica SET state=? WHERE file_id=? AND site='LOCAL'", state, seed.fileId()), state);
        }
        assertEquals(1, jdbc.update("UPDATE eatwhat_file.file_replica SET state='DELETED', updated_at=clock_timestamp() WHERE file_id=? AND site='LOCAL'", seed.fileId()));
        assertEquals("DELETED", replicaState(seed.fileId()));
    }

    // =====================================================================================================
    // business rows that name a key without a file_reference (inbound sync, conflict resolution)
    // =====================================================================================================

    @Test
    void businessRegistryCoversEveryImageLikeColumnInThePublicSchema() {
        java.util.Set<String> registered = new java.util.TreeSet<>();
        for (var column : you.v50to.eatwhat.service.storage.replication.BusinessKeyColumns.ALL) {
            registered.add(column.table() + "." + column.column());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema||'.'||table_name=? AND column_name=?",
                    Integer.class, column.table(), column.column()), "registered column exists: " + column);
        }
        java.util.Set<String> actual = new java.util.TreeSet<>(jdbc.queryForList("""
                SELECT table_schema||'.'||table_name||'.'||column_name FROM information_schema.columns
                WHERE table_schema='public' AND (column_name ILIKE '%avatar%' OR column_name ILIKE '%picture%' OR column_name ILIKE '%image%'
                       OR column_name ILIKE '%photo%' OR column_name ILIKE '%cover%' OR column_name ILIKE '%thumbnail%')
                """, String.class));
        assertEquals(registered, actual, "a new image/file-key column must be added to BusinessKeyColumns");
    }

    @Test
    void avatarRewrittenDirectlyAfterTheReleaseBlocksTheGc() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        service.detect();
        // Inbound sync (not UserService) puts the key back into users.avatar without any file_reference.
        jdbc.update("UPDATE users SET avatar=? WHERE id=?", seed.logicalKey(), seed.ownerId());

        assertSurvives(seed, service.run(), "BLOCKED", "BUSINESS_REFERENCE_PRESENT");

        jdbc.update("UPDATE users SET avatar='avatar/other.png' WHERE id=?", seed.ownerId());
        assertEquals(1, service.run().deleted(), "an unrelated avatar value does not block");
        assertFalse(exists(seed.finalKey()));
    }

    @Test
    void restaurantFoodAndActivityRowsThatNameTheKeyBlockTheGcButSoftDeletedActivityRowsDoNot() throws Exception {
        for (String target : List.of("restaurant", "foods", "activity_foods", "activity_dinners")) {
            store.deletes.set(0); // each target is its own scenario; the previous one legitimately deleted once
            Seed seed = seedUnreferenced(true, "PRIVATE");
            service.detect();
            long id = insertBusinessRow(target, seed, false);

            if(target.equals("foods")) {
                // V34 attaches a real reference for an owner/biz-matching projection, so the stronger live-ref
                // guard revives the candidate. Other cases deliberately use a foods alias in a different biz.
                assertSurvives(seed, service.run(), "REVIVED", "LIVE_REFERENCE");
            } else assertSurvives(seed, service.run(), "BLOCKED", "BUSINESS_REFERENCE_PRESENT");
            assertTrue(exists(seed.finalKey()), target);

            if (target.startsWith("activity_")) {
                jdbc.update("UPDATE " + target + " SET deleted_at=now() WHERE id=?", id);
                assertEquals(1, service.run().deleted(), "a soft-deleted " + target + " row no longer pins the file");
                assertFalse(exists(seed.finalKey()));
            } else if (target.equals("foods")) {
                jdbc.update("UPDATE foods SET picture_url=ARRAY['unrelated.png'] WHERE id=?", id);
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_reference_live WHERE logical_key=?",Integer.class,seed.logicalKey()));
                ageTombstone(seed);
                // Synthetic CDN completion: the newly attached public reference requires revocation first.
                jdbc.update("UPDATE eatwhat_file.file_cdn_invalidation SET status='DONE',completed_at=clock_timestamp() WHERE file_id=?",seed.fileId());
                service.detect();
                assertEquals(1, service.run().deleted(), "clearing the column, retention and CDN completion release the file");
            } else {
                jdbc.update("UPDATE restaurant SET picture_url=NULL WHERE id=?", id);
                assertEquals(1, service.run().deleted(), "clearing the column releases the file");
            }
        }
    }

    /** Inserts one business row whose picture column holds the revision's logical key. */
    private long insertBusinessRow(String table, Seed seed, boolean deleted) {
        return switch (table) {
            case "restaurant" -> jdbc.queryForObject("""
                    INSERT INTO restaurant(name,location,gcj_lng,gcj_lat,account_id,picture_url)
                    VALUES(?,ST_SetSRID(ST_Point(117,36),4326),117,36,?,ARRAY[?]) RETURNING id
                    """, Long.class, "r" + uuid().substring(0, 12), seed.ownerId(), seed.logicalKey());
            case "foods" -> {
                long restaurant = jdbc.queryForObject("""
                        INSERT INTO restaurant(name,location,gcj_lng,gcj_lat,account_id)
                        VALUES(?,ST_SetSRID(ST_Point(117,36),4326),117,36,?) RETURNING id
                        """, Long.class, "r" + uuid().substring(0, 12), seed.ownerId());
                yield jdbc.queryForObject("INSERT INTO foods(account_id,restaurant_id,name,picture_url) VALUES (?,?,?,ARRAY[?]) RETURNING id",
                        Long.class, seed.ownerId(), restaurant, "f" + uuid().substring(0, 12), seed.logicalKey());
            }
            case "activity_foods" -> jdbc.queryForObject("INSERT INTO activity_foods(account_id,food_name,picture_url) VALUES (?,?,ARRAY[?]) RETURNING id",
                    Long.class, seed.ownerId(), "a" + uuid().substring(0, 12), seed.logicalKey());
            case "activity_dinners" -> jdbc.queryForObject("INSERT INTO activity_dinners(account_id,picture_url) VALUES (?,ARRAY[?]) RETURNING id",
                    Long.class, seed.ownerId(), seed.logicalKey());
            default -> throw new IllegalArgumentException(table);
        };
    }

    // =====================================================================================================
    // stuck DELETING alert and the DELETE invalidation task
    // =====================================================================================================

    @Test
    void aDeletingCandidateThatKeepsFailingRaisesOneStuckAlert() throws Exception {
        props.setStuckAlertAfterAttempts(2);
        Seed seed = seedUnreferenced(true, "PRIVATE");
        service.detect();
        store.failDelete = new IllegalStateException("simulated delete outage");

        service.run();
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_replica_alert WHERE file_id=? AND alert_type='GC_DELETE_STUCK'", Integer.class, seed.fileId()),
                "one failure is not yet stuck");
        service.run();
        service.run();

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_replica_alert WHERE file_id=? AND alert_type='GC_DELETE_STUCK'", Integer.class, seed.fileId()),
                "exactly one alert once attempts >= the threshold");
        assertEquals("DELETING", replicaState(seed.fileId()));
        store.failDelete = null;
        assertEquals(1, service.run().deleted());
    }

    @Test
    void phaseOneEnqueuesOneDeleteInvalidationForAPublicRevisionAndItNeverBlocksTheDelete() throws Exception {
        Seed seed = seedUnreferenced(true, "PUBLIC");
        jdbc.update("UPDATE eatwhat_file.file_cdn_invalidation SET status='DONE', completed_at=clock_timestamp() WHERE file_id=? AND reason='REVOKE'", seed.fileId());
        service.detect();
        store.failDelete = new IllegalStateException("simulated delete outage");

        service.run(); // phase 1 committed, the delete failed

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_cdn_invalidation WHERE file_id=? AND reason='DELETE' AND status='READY'", Integer.class, seed.fileId()),
                "the DELETE task was enqueued atomically with the DELETING commit");
        store.failDelete = null;
        RunResult resumed = service.run();

        assertEquals(1, resumed.deleted(), "the READY DELETE task does not block phase 2 / the resume of its own candidate");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_cdn_invalidation WHERE file_id=? AND reason='DELETE'", Integer.class, seed.fileId()),
                "the resume does not enqueue another one");
        assertEquals(0, service.run().examined());
    }

    @Test
    void aPrivateOnlyRevisionGetsNoDeleteInvalidation() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        service.detect();

        assertEquals(1, service.run().deleted());

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_cdn_invalidation WHERE file_id=?", Integer.class, seed.fileId()));
    }

    @Test
    void v31CandidateChecksTriggersAndImmutability() {
        UUID id = UUID.randomUUID();
        String key = "staging/" + uuid();
        String insert = "INSERT INTO eatwhat_file.file_gc_candidate(candidate_id, site, object_key, class, state, lease_until, deleted_at, fencing_token) VALUES (?,?,?,?,?,%s,%s,%s)";
        // LEASED requires a lease; every other state forbids one.
        assertThrows(DataAccessException.class, () -> jdbc.update(String.format(insert, "NULL", "NULL", "0"), id, "LOCAL", key, "STAGING", "LEASED"));
        assertThrows(DataAccessException.class, () -> jdbc.update(String.format(insert, "now()", "NULL", "0"), id, "LOCAL", key, "STAGING", "DETECTED"));
        // DELETED requires deleted_at.
        assertThrows(DataAccessException.class, () -> jdbc.update(String.format(insert, "NULL", "NULL", "0"), id, "LOCAL", key, "STAGING", "DELETED"));
        // Enumerations and the non-negative fencing token.
        assertThrows(DataAccessException.class, () -> jdbc.update(String.format(insert, "NULL", "NULL", "0"), id, "LOCAL", key, "BOGUS", "DETECTED"));
        assertThrows(DataAccessException.class, () -> jdbc.update(String.format(insert, "NULL", "NULL", "0"), id, "LOCAL", key, "STAGING", "BOGUS"));
        assertThrows(DataAccessException.class, () -> jdbc.update(String.format(insert, "NULL", "NULL", "0"), id, "EDGE", key, "STAGING", "DETECTED"));
        assertThrows(DataAccessException.class, () -> jdbc.update(String.format(insert, "NULL", "NULL", "-1"), id, "LOCAL", key, "STAGING", "DETECTED"));
        // UNREFERENCED_REVISION must name its revision.
        assertThrows(DataAccessException.class, () -> jdbc.update(String.format(insert, "NULL", "NULL", "0"), id, "LOCAL", key, "UNREFERENCED_REVISION", "DETECTED"));

        assertEquals(1, jdbc.update(String.format(insert, "NULL", "NULL", "0"), id, "LOCAL", key, "STAGING", "DETECTED"));
        // (site, object_key) is unique.
        assertThrows(DataAccessException.class, () -> jdbc.update(String.format(insert, "NULL", "NULL", "0"), UUID.randomUUID(), "LOCAL", key, "STAGING", "DETECTED"));
        // The same key on the other site is a different row.
        assertEquals(1, jdbc.update(String.format(insert, "NULL", "NULL", "0"), UUID.randomUUID(), "PEER", key, "STAGING", "DETECTED"));

        // Identity is immutable.
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE eatwhat_file.file_gc_candidate SET object_key='staging/x' WHERE candidate_id=?", id));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE eatwhat_file.file_gc_candidate SET site='PEER', object_key='staging/y' WHERE candidate_id=?", id));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE eatwhat_file.file_gc_candidate SET class='ORPHAN_CANDIDATE' WHERE candidate_id=?", id));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE eatwhat_file.file_gc_candidate SET created_at=now() - interval '1 day' WHERE candidate_id=?", id));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE eatwhat_file.file_gc_candidate SET candidate_id=? WHERE candidate_id=?", UUID.randomUUID(), id));
        // Rows are never deleted.
        assertThrows(DataAccessException.class, () -> jdbc.update("DELETE FROM eatwhat_file.file_gc_candidate WHERE candidate_id=?", id));
        assertThrows(DataAccessException.class, () -> jdbc.update("DELETE FROM eatwhat_file.file_gc_candidate"));

        // Mutable columns and the lease CHECK in the legal direction.
        assertEquals(1, jdbc.update("UPDATE eatwhat_file.file_gc_candidate SET state='LEASED', lease_until=now() + interval '1 minute', fencing_token=fencing_token+1 WHERE candidate_id=?", id));
        assertEquals(1, jdbc.update("UPDATE eatwhat_file.file_gc_candidate SET state='ELIGIBLE', lease_until=NULL WHERE candidate_id=?", id));
        // DELETED is reachable only from LEASED, and is terminal; KEPT is terminal.
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE eatwhat_file.file_gc_candidate SET state='DELETED', deleted_at=now() WHERE candidate_id=?", id));
        assertEquals(1, jdbc.update("UPDATE eatwhat_file.file_gc_candidate SET state='LEASED', lease_until=now() + interval '1 minute' WHERE candidate_id=?", id));
        assertEquals(1, jdbc.update("UPDATE eatwhat_file.file_gc_candidate SET state='DELETED', deleted_at=now(), lease_until=NULL WHERE candidate_id=?", id));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE eatwhat_file.file_gc_candidate SET state='DETECTED' WHERE candidate_id=?", id));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE eatwhat_file.file_gc_candidate SET state='ELIGIBLE' WHERE candidate_id=?", id));
        assertEquals(1, jdbc.update("UPDATE eatwhat_file.file_gc_candidate SET last_error='still updatable', updated_at=now() WHERE candidate_id=?", id));
        UUID kept = UUID.randomUUID();
        assertEquals(1, jdbc.update(String.format(insert, "NULL", "NULL", "0"), kept, "LOCAL", "staging/" + uuid(), "STAGING", "KEPT"));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE eatwhat_file.file_gc_candidate SET state='DETECTED' WHERE candidate_id=?", kept));
    }

    @Test
    void v31ReferenceGuardBlocksReferenceAndRestoreOfADeletedObjectButAllowsRelease() throws Exception {
        Seed seed = seedUnreferenced(true, "PRIVATE");
        // Before deletion a restore is fine and a release is fine.
        assertEquals(1, jdbc.update("INSERT INTO eatwhat_file.file_reference_event(logical_key, ref_kind, ref_id, action, reason) VALUES (?, 'food', 'r1', 'RESTORE', 'ADMIN')", seed.logicalKey()));
        assertEquals(1, jdbc.update("INSERT INTO eatwhat_file.file_reference_event(logical_key, ref_kind, ref_id, action, reason) VALUES (?, 'food', 'r1', 'RELEASE', 'ENTITY_DELETED')", seed.logicalKey()));
        jdbc.update("UPDATE eatwhat_file.file_replica SET state='DELETED' WHERE file_id=? AND site='LOCAL'", seed.fileId());

        // After deletion releasing is still allowed (it only makes the file more unreferenced); nothing can reference or restore it.
        assertEquals(1, jdbc.update("INSERT INTO eatwhat_file.file_reference_event(logical_key, ref_kind, ref_id, action, reason) VALUES (?, 'food', 'r1', 'RELEASE', 'ENTITY_DELETED')", seed.logicalKey()));
        assertThrows(DataAccessException.class, () -> jdbc.update("INSERT INTO eatwhat_file.file_reference_event(logical_key, ref_kind, ref_id, action, reason) VALUES (?, 'food', 'r1', 'RESTORE', 'ADMIN')", seed.logicalKey()));
        assertThrows(DataAccessException.class, () -> jdbc.update("INSERT INTO eatwhat_file.file_reference(logical_key, ref_kind, ref_id) VALUES (?, 'food', 'new')", seed.logicalKey()));
    }

    // =====================================================================================================
    // helpers: assertions
    // =====================================================================================================

    /** The object must be intact and the candidate must have landed in the expected non-deleted state. */
    private void assertSurvives(Seed seed, RunResult result, String state, String reason) {
        assertEquals(0, result.deleted(), "nothing may be deleted");
        assertEquals(0, store.deletes.get(), "the store must never be asked to delete");
        assertTrue(exists(seed.finalKey()), "the object must still be in the bucket");
        Map<String, Object> row = candidate(seed.finalKey());
        assertEquals(state, row.get("state"));
        assertEquals(reason, row.get("block_reason"));
        assertNull(row.get("deleted_at"));
        assertNull(row.get("lease_until"));
        assertNotEquals("DELETED", replicaState(seed.fileId()));
    }

    /** Number of deleted keys under verified/ (staging deletions of an orphan's own expired intent are not counted). */
    private long verifiedDeletes() {
        return store.deletedKeys.stream().filter(k -> k.startsWith("verified/")).count();
    }

    // =====================================================================================================
    // helpers: seeding and reading
    // =====================================================================================================

    private record Seed(UUID fileId, String finalKey, String logicalKey, long ownerId, UUID ownerGid) { }

    private record Orphan(UUID orphanId, UUID intentId, String key) { }

    private record Staging(UUID intentId, String key) { }

    /**
     * A VERIFIED revision with LOCAL AVAILABLE_LOCAL replica and alias, one reference that was released, the object in the
     * bucket, and a tombstone aged one hour into the past. {@code withIntent=false} makes it look peer-originated.
     */
    private Seed seedUnreferenced(boolean withIntent, String visibility) throws Exception {
        long owner = insertUser();
        UUID ownerGid = ownerGid(owner);
        UUID fileId = UUID.randomUUID();
        String logicalKey = "foods/" + owner + "/" + uuid();
        String finalKey = "verified/" + uuid();
        if (withIntent) {
            seedIntent(owner, ownerGid, logicalKey, "staging/" + uuid(), "COMPLETE", "now() + interval '1 hour'", null);
        }
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key) VALUES (?,?, 'foods', ?)", fileId, ownerGid, logicalKey);
        jdbc.update("""
                INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at)
                VALUES (?,1,?,?,10,?,'VERIFIED', clock_timestamp())
                """, fileId, finalKey, SHA, CONTENT_TYPE);
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state) VALUES (?,1,'LOCAL',?,'AVAILABLE_LOCAL')", fileId, finalKey);
        jdbc.update("INSERT INTO eatwhat_file.file_key_alias(logical_key, file_id, revision, owner_gid, biz) VALUES (?,?,1,?, 'foods')", logicalKey, fileId, ownerGid);
        jdbc.update("INSERT INTO eatwhat_file.file_reference(logical_key, ref_kind, ref_id, visibility) VALUES (?, 'food', 'r1', ?)", logicalKey, visibility);
        put(realStore, finalKey, 10);
        jdbc.update("INSERT INTO eatwhat_file.file_reference_event(logical_key, ref_kind, ref_id, action, reason) VALUES (?, 'food', 'r1', 'RELEASE', 'ENTITY_DELETED')", logicalKey);
        Seed seed = new Seed(fileId, finalKey, logicalKey, owner, ownerGid);
        ageTombstone(seed);
        return seed;
    }

    private void ageTombstone(Seed seed) {
        assertEquals(1, jdbc.update("UPDATE eatwhat_file.file_tombstone SET unreferenced_since=clock_timestamp() - interval '1 hour' WHERE file_id=?", seed.fileId()));
    }

    private Orphan seedOrphan(String orphanState, String intentStatus, String expiresExpr, String leaseExpr, String updatedExpr, boolean putObject) throws Exception {
        long owner = insertUser();
        String key = "verified/" + uuid();
        UUID intentId = seedIntent(owner, UUID.randomUUID(), "foods/" + uuid(), "staging/" + uuid(), intentStatus, expiresExpr, leaseExpr);
        UUID orphanId = UUID.randomUUID();
        jdbc.update("INSERT INTO eatwhat_file.file_orphan_candidate(candidate_id, intent_id, fencing_token, object_key, state, created_at, updated_at) VALUES (?,?,1,?,?, "
                + updatedExpr + ", " + updatedExpr + ")", orphanId, intentId, key, orphanState);
        if (putObject) {
            put(realStore, key, 6);
        }
        return new Orphan(orphanId, intentId, key);
    }

    private Staging seedStaging(String status, String expiresExpr, String leaseExpr, String stagingKey, boolean putObject) throws Exception {
        UUID intentId = seedIntent(insertUser(), UUID.randomUUID(), "foods/" + uuid(), stagingKey, status, expiresExpr, leaseExpr);
        if (putObject) {
            put(realStore, stagingKey, 4);
        }
        return new Staging(intentId, stagingKey);
    }

    private UUID seedIntent(long owner, UUID ownerGid, String logicalKey, String stagingKey, String status, String expiresExpr, String leaseExpr) {
        UUID intentId = UUID.randomUUID();
        jdbc.update("INSERT INTO eatwhat_file.upload_intent(intent_id, owner_id, owner_gid, biz, logical_key, staging_key, declared_size, declared_type, expires_at, status, lease_until)"
                        + " VALUES (?,?,?,'foods',?,?,10,?," + expiresExpr + ",?," + (leaseExpr == null ? "NULL" : leaseExpr) + ")",
                intentId, owner, ownerGid, logicalKey, stagingKey, CONTENT_TYPE, status);
        return intentId;
    }

    /** The user's real global identity, so the lifecycle service's ownership checks pass for seeded files. */
    private UUID ownerGid(long userId) {
        return jdbc.queryForObject("SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id=?", UUID.class, userId);
    }

    private long insertUser() {
        return jdbc.queryForObject("INSERT INTO users(nick_name,password_hash) VALUES (?, 'synthetic-not-a-password') RETURNING id",
                Long.class, "u" + uuid().substring(0, 20));
    }

    private boolean exists(String key) {
        return realStore.head(key).isPresent();
    }

    private String replicaState(UUID fileId) {
        return jdbc.queryForObject("SELECT state FROM eatwhat_file.file_replica WHERE file_id=? AND site='LOCAL'", String.class, fileId);
    }

    private String orphanState(String key) {
        return jdbc.queryForObject("SELECT state FROM eatwhat_file.file_orphan_candidate WHERE object_key=?", String.class, key);
    }

    private Map<String, Object> candidate(String key) {
        return jdbc.queryForMap("SELECT * FROM eatwhat_file.file_gc_candidate WHERE site='LOCAL' AND object_key=?", key);
    }

    private UUID candidateId(String key) {
        return (UUID) candidate(key).get("candidate_id");
    }

    private String intentJson(UUID intentId) {
        return jdbc.queryForObject("SELECT to_jsonb(i)::text FROM eatwhat_file.upload_intent i WHERE intent_id=?", String.class, intentId);
    }

    private String candidateSnapshot() {
        return jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(c) ORDER BY to_jsonb(c)::text), '[]'::jsonb)::text FROM eatwhat_file.file_gc_candidate c", String.class);
    }

    private Map<String, String> snapshotBusinessTables() {
        Map<String, String> snapshot = new TreeMap<>();
        for (String table : List.of("file_replica", "file_transfer_job", "file_revision", "file_publication", "file_orphan_candidate",
                "upload_intent", "file_tombstone", "file_reference", "file_reference_event", "file_object", "file_key_alias")) {
            snapshot.put(table, jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text), '[]'::jsonb)::text FROM eatwhat_file."
                    + table + " t", String.class));
        }
        return snapshot;
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static void put(S3ReplicaObjectStore target, String key, int size) throws Exception {
        Path file = Files.createTempFile("gc-", ".bin");
        try {
            Files.write(file, new byte[size]);
            target.putFile(key, CONTENT_TYPE, file, SHA);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    // =====================================================================================================
    // helpers: stores
    // =====================================================================================================

    /** Wraps the real store: counts every call and can inject failures or block inside deleteObject. */
    private static final class RecordingStore implements DeletableObjectStore {
        private final S3ReplicaObjectStore delegate;
        final AtomicInteger totalCalls = new AtomicInteger();
        final AtomicInteger deletes = new AtomicInteger();
        final List<String> deletedKeys = new CopyOnWriteArrayList<>();
        volatile RuntimeException failDelete;
        volatile RuntimeException failHead;
        volatile boolean applyThenFail;
        volatile boolean ignoreDeletes;
        /** Throw an AbortedException (and set the thread's interrupt flag) from deleteObject; applies the delete first when applyThenFail. */
        volatile boolean abortLikeInterrupt;
        volatile CountDownLatch deleteEntered;
        volatile CountDownLatch deleteProceed;

        RecordingStore(S3ReplicaObjectStore delegate) {
            this.delegate = delegate;
        }

        void resetCounters() {
            totalCalls.set(0);
            deletes.set(0);
            deletedKeys.clear();
        }

        @Override public Optional<ObjectMeta> head(String key) {
            totalCalls.incrementAndGet();
            RuntimeException failure = failHead;
            if (failure != null) throw failure;
            return delegate.head(key);
        }

        @Override public DigestResult downloadTo(String key, Path tmp, long maxBytes) {
            totalCalls.incrementAndGet();
            return delegate.downloadTo(key, tmp, maxBytes);
        }

        @Override public void putFile(String key, String contentType, Path src, String sha256Hex) {
            totalCalls.incrementAndGet();
            delegate.putFile(key, contentType, src, sha256Hex);
        }

        @Override public DigestResult readbackDigest(String key, long maxBytes) {
            totalCalls.incrementAndGet();
            return delegate.readbackDigest(key, maxBytes);
        }

        @Override public ObjectPage listObjects(String prefix, String continuationToken, int maxKeys) {
            totalCalls.incrementAndGet();
            return delegate.listObjects(prefix, continuationToken, maxKeys);
        }

        @Override public void deleteObject(String key) {
            totalCalls.incrementAndGet();
            deletes.incrementAndGet();
            deletedKeys.add(key);
            CountDownLatch entered = deleteEntered;
            if (entered != null) {
                entered.countDown();
                try {
                    if (!deleteProceed.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("test release timeout");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
            if (abortLikeInterrupt) {
                if (applyThenFail) delegate.deleteObject(key);
                Thread.currentThread().interrupt();
                throw software.amazon.awssdk.core.exception.AbortedException.create("interrupted while deleting");
            }
            RuntimeException failure = failDelete;
            if (failure != null) {
                if (applyThenFail) delegate.deleteObject(key);
                throw failure;
            }
            if (!ignoreDeletes) delegate.deleteObject(key);
        }
    }

    /** A store that can list and read but not delete: the type system must keep the GC from deleting through it. */
    private static final class ReadOnlyStore implements you.v50to.eatwhat.service.storage.replication.InventoryObjectStore {
        private final S3ReplicaObjectStore delegate;

        ReadOnlyStore(S3ReplicaObjectStore delegate) {
            this.delegate = delegate;
        }

        @Override public Optional<ObjectMeta> head(String key) { return delegate.head(key); }
        @Override public DigestResult downloadTo(String key, Path tmp, long maxBytes) { return delegate.downloadTo(key, tmp, maxBytes); }
        @Override public void putFile(String key, String contentType, Path src, String sha256Hex) { delegate.putFile(key, contentType, src, sha256Hex); }
        @Override public DigestResult readbackDigest(String key, long maxBytes) { return delegate.readbackDigest(key, maxBytes); }
        @Override public ObjectPage listObjects(String prefix, String continuationToken, int maxKeys) { return delegate.listObjects(prefix, continuationToken, maxKeys); }
    }

    /** FileObjectStorage whose staging area is in memory and whose immutable PUTs go to the real bucket. */
    private static final class BucketBackedStorage implements FileObjectStorage {
        private final Map<String, byte[]> staging = new ConcurrentHashMap<>();
        private final S3ReplicaObjectStore bucket;
        volatile Runnable afterPut;

        BucketBackedStorage(S3ReplicaObjectStore bucket) {
            this.bucket = bucket;
        }

        void stage(String key, byte[] bytes) {
            staging.put(key, bytes.clone());
        }

        @Override public String presignStagingPut(String stagingKey, String contentType, long ttlSeconds) {
            return "https://objects.example.invalid/" + stagingKey;
        }

        @Override public StagedMetadata head(String key) {
            byte[] bytes = staging.get(key);
            if (bytes == null) throw new IllegalStateException("staging object missing");
            return new StagedMetadata(bytes.length, "image/png");
        }

        @Override public StagedMetadata downloadToFile(String key, Path destination, long maxBytes) {
            byte[] bytes = staging.get(key);
            if (bytes == null) throw new IllegalStateException("staging object missing");
            try {
                Files.write(destination, bytes);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            return new StagedMetadata(bytes.length, "image/png");
        }

        @Override public void putImmutableFile(String key, String contentType, Path source) {
            try {
                String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
                bucket.putFile(key, contentType, source, sha);
            } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            Runnable hook = afterPut;
            if (hook != null) hook.run();
        }
    }
}
