package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import you.v50to.eatwhat.config.FileCdnInvalidationProperties;
import you.v50to.eatwhat.config.FileGcProperties;
import you.v50to.eatwhat.config.FileLifecycleProperties;
import you.v50to.eatwhat.config.FileSchemaMigrationCallback;
import you.v50to.eatwhat.config.S3StorageProperties;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.config.identity.GlobalIdentityMigrationCallback;
import you.v50to.eatwhat.data.enums.UploadBiz;
import you.v50to.eatwhat.service.storage.FileLifecycleService;
import you.v50to.eatwhat.service.storage.FileObjectStorage;
import you.v50to.eatwhat.service.storage.replication.CdnInvalidationProvider;
import you.v50to.eatwhat.service.storage.replication.FileCdnInvalidationStore;
import you.v50to.eatwhat.service.storage.replication.FileCdnInvalidationWorker;
import you.v50to.eatwhat.service.storage.replication.InMemoryFakeCdnProvider;
import you.v50to.eatwhat.service.storage.replication.FileGcService;
import you.v50to.eatwhat.service.storage.replication.FileGcService.Mode;
import you.v50to.eatwhat.service.storage.replication.FileGcService.RunResult;
import you.v50to.eatwhat.service.storage.replication.FileInventoryService;
import you.v50to.eatwhat.service.storage.replication.FilePublicationService;
import you.v50to.eatwhat.service.storage.replication.FileReplicationEnqueuer;
import you.v50to.eatwhat.service.storage.replication.FileTransferJobStore;
import you.v50to.eatwhat.service.storage.replication.S3ReplicaObjectStore;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the GC, the inventory, the lifecycle service (complete / reference / release / restore) and the replication
 * enqueuer through a NON-superuser role that has only what FileSchemaMigrationCallback grants. Any
 * "permission denied" here means a missing GRANT.
 */
class FileGcRestrictedRoleIT {
    private static final String REGION = "us-east-1";
    private static final String APP_USER = "gc_restricted_app";
    private static final String APP_PASSWORD = "synthetic-restricted-only";
    private static final String DATABASE = "gc_restricted_db";
    private static final String CONTENT_TYPE = "application/octet-stream";
    private static final String SHA = "a".repeat(64);
    private static final byte[] PNG = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3, 4};

    private static GenericContainer<?> postgres;
    private static GenericContainer<?> rustfs;
    private static String endpoint;
    private static String url;

    @BeforeAll
    static void start() throws Exception {
        postgres = IsolatedContainers.postgres("gc-restricted");
        postgres.start();
        rustfs = IsolatedContainers.rustfs("gc-restricted");
        rustfs.start();
        endpoint = "http://" + rustfs.getHost() + ":" + rustfs.getMappedPort(9000);

        JdbcTemplate server = new JdbcTemplate(new DriverManagerDataSource(
                IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE), IsolatedContainers.USER, IsolatedContainers.PASSWORD));
        server.execute("CREATE ROLE " + APP_USER + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '" + APP_PASSWORD + "'");
        server.execute("CREATE DATABASE " + DATABASE + " TEMPLATE template0");
        url = IsolatedContainers.jdbcUrl(postgres, DATABASE);
        // What deploy/initdb/20-create-application-db.sh does for the application role.
        try (Connection admin = DriverManager.getConnection(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD);
             Statement statement = admin.createStatement()) {
            statement.execute("REVOKE ALL ON DATABASE " + DATABASE + " FROM PUBLIC");
            statement.execute("GRANT CONNECT ON DATABASE " + DATABASE + " TO " + APP_USER);
            statement.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            statement.execute("GRANT USAGE ON SCHEMA public TO " + APP_USER);
            statement.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + APP_USER);
            statement.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO " + APP_USER);
        }
        Flyway.configure().dataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true)
                .callbacks(new GlobalIdentityMigrationCallback(new SiteProperties(), APP_USER),
                        new FileSchemaMigrationCallback(APP_USER))
                .load().migrate();
    }

    @AfterAll
    static void stop() {
        if (rustfs != null) rustfs.stop();
        if (postgres != null) postgres.stop();
    }

    @Test
    void gcInventoryLifecycleAndEnqueuerWorkThroughTheRestrictedRole() throws Exception {
        DriverManagerDataSource appSource = new DriverManagerDataSource(url, APP_USER, APP_PASSWORD);
        JdbcTemplate app = new JdbcTemplate(appSource);
        DataSourceTransactionManager appTx = new DataSourceTransactionManager(appSource);
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD));

        // Sanity: this really is the restricted role.
        assertEquals(APP_USER, app.queryForObject("SELECT current_user", String.class));
        assertEquals(Boolean.FALSE, app.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname=current_user", Boolean.class));
        assertThrows(DataAccessException.class, () -> app.update("DELETE FROM eatwhat_file.file_gc_candidate"),
                "the runtime role must not be able to delete candidates");

        String bucket = "gc-r-" + uuid().substring(0, 12);
        try (S3Client s3 = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build()) {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
        try (S3ReplicaObjectStore store = new S3ReplicaObjectStore(endpoint, REGION, bucket,
                IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY, true)) {

            // ---- lifecycle through the restricted role: presign, complete (canonicalize), reference, release, restore ----
            long owner = app.queryForObject("INSERT INTO users(nick_name,password_hash) VALUES (?, 'synthetic') RETURNING id",
                    Long.class, "gcr" + uuid().substring(0, 16));
            S3StorageProperties storage = new S3StorageProperties();
            storage.setMaxFileSizeBytes(1024);
            storage.setAllowedContentTypes(List.of("image/png"));
            FileLifecycleProperties lifecycle = new FileLifecycleProperties();
            lifecycle.setLifecycleEnabled(true);
            BucketBackedStorage objects = new BucketBackedStorage(store);
            FileLifecycleService files = new FileLifecycleService(app, objects, storage, lifecycle, appTx);

            String unreferencedKey = completeFile(app, files, objects, owner, "a.png");
            files.finalizeAndReference(owner, UploadBiz.FOODS, unreferencedKey, "food", "gone", "PRIVATE");
            assertEquals(1, files.releaseReferences("food", "gone", null, "ENTITY_DELETED"));
            UUID unreferencedFile = app.queryForObject("SELECT file_id FROM eatwhat_file.file_key_alias WHERE logical_key=?", UUID.class, unreferencedKey);
            String unreferencedObject = app.queryForObject("SELECT final_key FROM eatwhat_file.file_revision WHERE file_id=?", String.class, unreferencedFile);
            assertFalse(app.queryForObject("SELECT had_public_ref FROM eatwhat_file.file_tombstone WHERE file_id=?", Boolean.class, unreferencedFile));

            // A PUBLIC revision: the release enqueues a REVOKE task through the tombstone trigger (as the restricted role).
            String publicKey = completeFile(app, files, objects, owner, "c.png");
            files.finalizeAndReference(owner, UploadBiz.FOODS, publicKey, "food", "public-gone", "PUBLIC");
            assertEquals(1, files.releaseReferences("food", "public-gone", null, "ENTITY_DELETED"));
            UUID publicFile = app.queryForObject("SELECT file_id FROM eatwhat_file.file_key_alias WHERE logical_key=?", UUID.class, publicKey);
            String publicObject = app.queryForObject("SELECT final_key FROM eatwhat_file.file_revision WHERE file_id=?", String.class, publicFile);
            assertEquals(1, app.queryForObject("SELECT count(*) FROM eatwhat_file.file_cdn_invalidation WHERE file_id=? AND reason='REVOKE' AND status='READY'", Integer.class, publicFile),
                    "the tombstone trigger enqueued the REVOKE task under the restricted role");
            // release -> restore -> release again: generation 2 only after the first one finished (DONE below), and the
            // V32 tombstone fix makes unreferenced_since non-null again.
            String revivedKey = completeFile(app, files, objects, owner, "d.png");
            files.finalizeAndReference(owner, UploadBiz.FOODS, revivedKey, "food", "cycle", "PRIVATE");
            files.releaseReferences("food", "cycle", null, "ENTITY_DELETED");
            files.finalizeAndReference(owner, UploadBiz.FOODS, revivedKey, "food", "cycle", "PRIVATE");
            files.releaseReferences("food", "cycle", null, "ENTITY_DELETED");
            UUID revivedFile = app.queryForObject("SELECT file_id FROM eatwhat_file.file_key_alias WHERE logical_key=?", UUID.class, revivedKey);
            String revivedObject = app.queryForObject("SELECT final_key FROM eatwhat_file.file_revision WHERE file_id=?", String.class, revivedFile);
            assertNotNull(app.queryForObject("SELECT unreferenced_since FROM eatwhat_file.file_tombstone WHERE file_id=?", Object.class, revivedFile));

            String restoredKey = completeFile(app, files, objects, owner, "b.png");
            files.finalizeAndReference(owner, UploadBiz.FOODS, restoredKey, "food", "back", "PRIVATE");
            assertEquals(1, files.releaseReferences("food", "back", null, "ENTITY_DELETED"));
            files.finalizeAndReference(owner, UploadBiz.FOODS, restoredKey, "food", "back", "PRIVATE"); // RESTORE event
            assertEquals(1, app.queryForObject("SELECT count(*) FROM eatwhat_file.file_reference_live WHERE logical_key=?", Integer.class, restoredKey));
            UUID restoredFile = app.queryForObject("SELECT file_id FROM eatwhat_file.file_key_alias WHERE logical_key=?", UUID.class, restoredKey);
            String restoredObject = app.queryForObject("SELECT final_key FROM eatwhat_file.file_revision WHERE file_id=?", String.class, restoredFile);

            // ---- seeds that only an administrator can create: an expired staging object and an orphan ----
            String stagingKey = "staging/" + uuid();
            String orphanKey = "verified/" + uuid();
            UUID stagingIntent = seedIntent(admin, owner, "foods/" + uuid(), stagingKey, "PENDING", "now() - interval '2 hours'");
            UUID orphanIntent = seedIntent(admin, owner, "foods/" + uuid(), "staging/" + uuid(), "COMPLETE", "now() + interval '1 hour'");
            admin.update("INSERT INTO eatwhat_file.file_orphan_candidate(candidate_id, intent_id, fencing_token, object_key, state, created_at, updated_at)"
                    + " VALUES (?,?,1,?, 'ORPHANED', now() - interval '1 hour', now() - interval '1 hour')", UUID.randomUUID(), orphanIntent, orphanKey);
            put(store, stagingKey, 4);
            put(store, orphanKey, 6);
            // The unreferenced revision's tombstone is aged by the administrator (a retention stand-in).
            assertEquals(1, admin.update("UPDATE eatwhat_file.file_tombstone SET unreferenced_since=clock_timestamp() - interval '1 hour' WHERE file_id=?", unreferencedFile));
            assertNotNull(stagingIntent);
            assertEquals(1, admin.update("UPDATE eatwhat_file.file_tombstone SET unreferenced_since=clock_timestamp() - interval '1 hour' WHERE file_id=?", publicFile));
            assertEquals(1, admin.update("UPDATE eatwhat_file.file_tombstone SET unreferenced_since=clock_timestamp() - interval '1 hour' WHERE file_id=?", revivedFile));

            // ---- the GC itself, as the restricted role ----
            FileGcProperties props = new FileGcProperties();
            props.setEnabled(true);
            props.setInventoryEnabled(true);
            props.setSafetyWindow(Duration.ofSeconds(1));
            props.setRetention(Duration.ofSeconds(1));
            props.setMultipartTtl(Duration.ofSeconds(1));
            props.setDryRun(false);
            props.setExecuteEnabled(true);
            props.setExecuteClasses(new LinkedHashSet<>(FileGcProperties.EXECUTE_CLASSES));
            props.setExecuteAck("drill-restricted-role");
            props.setGcInterval(Duration.ZERO);
            SiteProperties site = new SiteProperties();
            site.setMode(SiteProperties.Mode.CAMPUS);
            FileGcService gc = new FileGcService(app, appTx, props, site, () -> store);

            assertEquals(5, gc.detect().total(), "staging, orphan, and three unreferenced revisions (private, revived-then-released, public)");
            RunResult result = gc.run();

            assertEquals(Mode.EXECUTE, result.mode());
            assertEquals(0, result.errors(), String.valueOf(result.error()));
            assertEquals(4, result.deleted(), "the public revision is held back by its READY invalidation task");
            assertTrue(store.head(publicObject).isPresent());
            assertEquals("CDN_INVALIDATION_PENDING", app.queryForObject("SELECT block_reason FROM eatwhat_file.file_gc_candidate WHERE object_key=?", String.class, publicObject));

            // ---- CDN invalidation worker as the restricted role: claim, fenced update to DONE ----
            FileCdnInvalidationProperties cdnProps = new FileCdnInvalidationProperties();
            cdnProps.setEnabled(true);
            InMemoryFakeCdnProvider fake = new InMemoryFakeCdnProvider();
            FileCdnInvalidationWorker cdnWorker = new FileCdnInvalidationWorker(new FileCdnInvalidationStore(app), fake, cdnProps,
                    () -> "https://cdn.example.test/pub", new java.util.Random(3));
            assertEquals(1, cdnWorker.runOnce());
            assertEquals("DONE", app.queryForObject("SELECT status FROM eatwhat_file.file_cdn_invalidation WHERE file_id=?", String.class, publicFile));
            assertEquals(List.of("/pub/" + publicObject), fake.received().get(0).paths());

            RunResult afterWorker = gc.run();
            assertEquals(1, afterWorker.deleted(), "once the invalidation is DONE the public revision is deleted (two-phase, as the restricted role)");
            assertTrue(store.head(publicObject).isEmpty());
            assertEquals("DELETED", app.queryForObject("SELECT state FROM eatwhat_file.file_replica WHERE file_id=? AND site='LOCAL'", String.class, publicFile));
            assertTrue(store.head(revivedObject).isEmpty());
            assertTrue(store.head(stagingKey).isEmpty(), "STAGING deleted");
            assertTrue(store.head(orphanKey).isEmpty(), "ORPHAN_CANDIDATE deleted");
            assertTrue(store.head(unreferencedObject).isEmpty(), "UNREFERENCED_REVISION deleted");
            assertEquals("DELETED", app.queryForObject("SELECT state FROM eatwhat_file.file_replica WHERE file_id=? AND site='LOCAL'", String.class, unreferencedFile));
            assertEquals("CLEANED", app.queryForObject("SELECT state FROM eatwhat_file.file_orphan_candidate WHERE object_key=?", String.class, orphanKey));
            assertEquals(5, app.queryForObject("SELECT count(*) FROM eatwhat_file.file_gc_candidate WHERE state='DELETED'", Integer.class));
            assertTrue(store.head(restoredObject).isPresent(), "a live, restored revision is untouched");
            assertEquals("AVAILABLE_LOCAL", app.queryForObject("SELECT state FROM eatwhat_file.file_replica WHERE file_id=? AND site='LOCAL'", String.class, restoredFile));

            // A reference to the deleted revision is refused for the role too (V31 trigger reads/locks as the invoker).
            assertThrows(RuntimeException.class, () -> files.finalizeAndReference(owner, UploadBiz.FOODS, unreferencedKey, "food", "again", "PRIVATE"));

            // ---- replication enqueuer: shared tombstone lock + DELETED re-check under the restricted role ----
            FileReplicationEnqueuer enqueuer = new FileReplicationEnqueuer(app,
                    new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                            .getBeanProvider(you.v50to.eatwhat.account.ConsentStore.class));
            assertEquals(1, enqueuer.enqueueMissing(10), "only the live revision is enqueued; the deleted one is skipped");
            assertEquals(0, app.queryForObject("SELECT count(*) FROM eatwhat_file.file_transfer_job WHERE file_id=?", Integer.class, unreferencedFile));

            // ---- multipart bookkeeping (V33 column) as the restricted role: fenced upload-id UPDATE and clear ----
            FileTransferJobStore restrictedJobs = new FileTransferJobStore(app, new FilePublicationService(app));
            var claimedJobs = restrictedJobs.claim(1, Duration.ofSeconds(60));
            assertEquals(1, claimedJobs.size());
            assertTrue(restrictedJobs.setMultipartUpload(claimedJobs.get(0), "synthetic-upload-id"));
            assertEquals("synthetic-upload-id", app.queryForObject(
                    "SELECT multipart_upload_id FROM eatwhat_file.file_transfer_job WHERE job_id=?", String.class, claimedJobs.get(0).jobId()));
            assertTrue(restrictedJobs.clearMultipartUpload(claimedJobs.get(0)));
            assertNull(app.queryForObject(
                    "SELECT multipart_upload_id FROM eatwhat_file.file_transfer_job WHERE job_id=?", String.class, claimedJobs.get(0).jobId()));
            assertTrue(restrictedJobs.retry(claimedJobs.get(0), Duration.ZERO, "TEST", null));

            // ---- inventory, as the restricted role (writes a run row, a finding via its sequence) ----
            String strayKey = "verified/" + uuid();
            put(store, strayKey, 3);
            // An abandoned multipart upload: the sweep (a GC EXECUTE run, as the restricted role) aborts it and records a finding.
            String staleMultipartKey = "verified/" + uuid();
            String staleUploadId;
            try (S3Client raw = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.of(REGION))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                            IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY)))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build()) {
                staleUploadId = raw.createMultipartUpload(b -> b.bucket(bucket).key(staleMultipartKey)).uploadId();
            }
            Thread.sleep(2500);
            FileGcProperties inventoryProps = new FileGcProperties();
            inventoryProps.setInventoryEnabled(true);
            inventoryProps.setSafetyWindow(Duration.ofSeconds(1));
            FileInventoryService inventory = new FileInventoryService(app, inventoryProps, () -> store, () -> null);
            FileInventoryService.InventoryResult inventoryResult = inventory.runInventory("LOCAL");
            assertNull(inventoryResult.error(), String.valueOf(inventoryResult.error()));
            assertEquals(0, inventoryResult.errors());
            assertTrue(inventoryResult.findings().getOrDefault("UNKNOWN_VERIFIED", 0L) >= 1, "the stray object is reported: " + inventoryResult.findings());
            assertTrue(store.head(strayKey).isPresent(), "inventory never deletes");

            // ---- a second GC run is a no-op through the same role ----
            RunResult again = gc.runCycle();
            assertEquals(0, again.deleted());
            assertEquals(0, again.errors(), String.valueOf(again.error()));
            assertEquals(1, again.multipartAborted(), "the stale multipart upload is aborted and recorded through the restricted role");
            assertEquals(1, app.queryForObject("SELECT count(*) FROM eatwhat_file.file_inventory_finding WHERE kind='STALE_MULTIPART' AND detail=?",
                    Integer.class, "ABORTED:" + staleUploadId));
            assertTrue(store.listMultipartUploads("verified/").stream().noneMatch(u -> u.uploadId().equals(staleUploadId)));

            // ---- CDN worker, quarantine + alert, retry + exhaustion alert (restricted role) ----
            // First the DELETE invalidation the GC enqueued (restricted role) for the public revision it deleted.
            assertEquals(1, app.queryForObject("SELECT count(*) FROM eatwhat_file.file_cdn_invalidation WHERE file_id=? AND reason='DELETE' AND status='READY'", Integer.class, publicFile));
            assertEquals(1, cdnWorker.runOnce());
            assertEquals("DONE", app.queryForObject("SELECT status FROM eatwhat_file.file_cdn_invalidation WHERE file_id=? AND reason='DELETE'", String.class, publicFile));
            String quarantineKey = completeFile(app, files, objects, owner, "q.png");
            files.finalizeAndReference(owner, UploadBiz.FOODS, quarantineKey, "food", "q-gone", "PUBLIC");
            files.releaseReferences("food", "q-gone", null, "ENTITY_DELETED");
            UUID quarantineFile = app.queryForObject("SELECT file_id FROM eatwhat_file.file_key_alias WHERE logical_key=?", UUID.class, quarantineKey);
            fake.enqueue(new CdnInvalidationProvider.Permanent("HTTP_400"));
            assertEquals(1, cdnWorker.runOnce());
            assertEquals("QUARANTINED", app.queryForObject("SELECT status FROM eatwhat_file.file_cdn_invalidation WHERE file_id=?", String.class, quarantineFile));
            assertEquals(1, app.queryForObject("SELECT count(*) FROM eatwhat_file.file_replica_alert WHERE file_id=? AND alert_type='CDN_INVALIDATION_FAILED'", Integer.class, quarantineFile));

            String retryKey = completeFile(app, files, objects, owner, "r.png");
            files.finalizeAndReference(owner, UploadBiz.FOODS, retryKey, "food", "r-gone", "PUBLIC");
            files.releaseReferences("food", "r-gone", null, "ENTITY_DELETED");
            UUID retryFile = app.queryForObject("SELECT file_id FROM eatwhat_file.file_key_alias WHERE logical_key=?", UUID.class, retryKey);
            cdnProps.setAlertAfterAttempts(1);
            fake.alwaysReturn(new CdnInvalidationProvider.Retryable("HTTP_503"));
            assertEquals(1, cdnWorker.runOnce());
            assertEquals("READY", app.queryForObject("SELECT status FROM eatwhat_file.file_cdn_invalidation WHERE file_id=?", String.class, retryFile));
            assertEquals(1, app.queryForObject("SELECT count(*) FROM eatwhat_file.file_replica_alert WHERE file_id=? AND alert_type='CDN_RETRY_EXHAUSTED'", Integer.class, retryFile));
            admin.update("UPDATE eatwhat_file.file_cdn_invalidation SET next_attempt_at=clock_timestamp() WHERE file_id=?", retryFile);
            assertEquals(1, cdnWorker.runOnce());
            assertEquals(2, app.queryForObject("SELECT attempts FROM eatwhat_file.file_cdn_invalidation WHERE file_id=?", Integer.class, retryFile));
            assertEquals(1, app.queryForObject("SELECT count(*) FROM eatwhat_file.file_replica_alert WHERE file_id=? AND alert_type='CDN_RETRY_EXHAUSTED'", Integer.class, retryFile),
                    "one alert, deduplicated");
            fake.alwaysReturn(new CdnInvalidationProvider.Accepted("recovered"));

            // ---- GC resume, stuck alert, recordFailure on a DELETING candidate, DELETE task (restricted role) ----
            String stuckKey = completeFile(app, files, objects, owner, "s.png");
            files.finalizeAndReference(owner, UploadBiz.FOODS, stuckKey, "food", "s-gone", "PUBLIC");
            files.releaseReferences("food", "s-gone", null, "ENTITY_DELETED");
            UUID stuckFile = app.queryForObject("SELECT file_id FROM eatwhat_file.file_key_alias WHERE logical_key=?", UUID.class, stuckKey);
            String stuckObject = app.queryForObject("SELECT final_key FROM eatwhat_file.file_revision WHERE file_id=?", String.class, stuckFile);
            admin.update("UPDATE eatwhat_file.file_cdn_invalidation SET status='DONE', completed_at=clock_timestamp() WHERE file_id=?", stuckFile);
            admin.update("UPDATE eatwhat_file.file_tombstone SET unreferenced_since=clock_timestamp() - interval '1 hour' WHERE file_id=?", stuckFile);
            props.setStuckAlertAfterAttempts(1);
            FlakyStore flaky = new FlakyStore(store);
            flaky.failDelete = true;
            FileGcService flakyGc = new FileGcService(app, appTx, props, site, () -> flaky);
            flakyGc.detect();

            RunResult failedDelete = flakyGc.run();

            assertEquals(1, failedDelete.errors());
            assertEquals("DELETING", app.queryForObject("SELECT state FROM eatwhat_file.file_replica WHERE file_id=? AND site='LOCAL'", String.class, stuckFile));
            assertEquals("DELETING", app.queryForObject("SELECT state FROM eatwhat_file.file_gc_candidate WHERE object_key=?", String.class, stuckObject));
            assertEquals(1, app.queryForObject("SELECT attempts FROM eatwhat_file.file_gc_candidate WHERE object_key=?", Integer.class, stuckObject), "recordFailure as the restricted role");
            assertEquals(1, app.queryForObject("SELECT count(*) FROM eatwhat_file.file_cdn_invalidation WHERE file_id=? AND reason='DELETE'", Integer.class, stuckFile), "DELETE task enqueued with phase 1");
            assertEquals(1, app.queryForObject("SELECT count(*) FROM eatwhat_file.file_replica_alert WHERE file_id=? AND alert_type='GC_DELETE_STUCK'", Integer.class, stuckFile));
            assertTrue(store.head(stuckObject).isPresent());

            // Phase 1 now fences first replication too. The restricted role can neither add a peer
            // nor derail the committed local deletion with a late catalog entry.
            var admission=assertThrows(DataAccessException.class,()->app.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state) VALUES (?,1,'PEER',?, 'PENDING_SYNC')", stuckFile, stuckObject));
            assertEquals("23514",assertInstanceOf(java.sql.SQLException.class,admission.getMostSpecificCause()).getSQLState());
            flaky.failDelete = false;
            assertEquals(1,flakyGc.run().deleted());
            assertEquals(0, app.queryForObject("SELECT count(*) FROM eatwhat_file.file_replica_alert WHERE file_id=? AND alert_type='GC_DELETING_BLOCKED'", Integer.class, stuckFile));
            assertEquals("DELETED", app.queryForObject("SELECT state FROM eatwhat_file.file_replica WHERE file_id=? AND site='LOCAL'", String.class, stuckFile));
            assertTrue(store.head(stuckObject).isEmpty());

            // ---- business-row liveness check (SELECT on the business tables as the restricted role) ----
            String pinnedKey = completeFile(app, files, objects, owner, "p.png");
            files.finalizeAndReference(owner, UploadBiz.FOODS, pinnedKey, "food", "p-gone", "PRIVATE");
            files.releaseReferences("food", "p-gone", null, "ENTITY_DELETED");
            UUID pinnedFile = app.queryForObject("SELECT file_id FROM eatwhat_file.file_key_alias WHERE logical_key=?", UUID.class, pinnedKey);
            String pinnedObject = app.queryForObject("SELECT final_key FROM eatwhat_file.file_revision WHERE file_id=?", String.class, pinnedFile);
            admin.update("UPDATE eatwhat_file.file_tombstone SET unreferenced_since=clock_timestamp() - interval '1 hour' WHERE file_id=?", pinnedFile);
            app.update("UPDATE users SET avatar=? WHERE id=?", pinnedKey, owner); // inbound-sync style write, no file_reference
            gc.detect();

            RunResult pinned = gc.run();

            assertEquals(0, pinned.deleted());
            assertEquals("BUSINESS_REFERENCE_PRESENT", app.queryForObject("SELECT block_reason FROM eatwhat_file.file_gc_candidate WHERE object_key=?", String.class, pinnedObject));
            assertTrue(store.head(pinnedObject).isPresent());
        }
    }

    /** Delete-failure injection around the real store. */
    private static final class FlakyStore implements you.v50to.eatwhat.service.storage.replication.DeletableObjectStore {
        private final S3ReplicaObjectStore delegate;
        volatile boolean failDelete;

        FlakyStore(S3ReplicaObjectStore delegate) {
            this.delegate = delegate;
        }

        @Override public java.util.Optional<ObjectMeta> head(String key) { return delegate.head(key); }
        @Override public DigestResult downloadTo(String key, Path tmp, long maxBytes) { return delegate.downloadTo(key, tmp, maxBytes); }
        @Override public void putFile(String key, String contentType, Path src, String sha256Hex) { delegate.putFile(key, contentType, src, sha256Hex); }
        @Override public DigestResult readbackDigest(String key, long maxBytes) { return delegate.readbackDigest(key, maxBytes); }
        @Override public ObjectPage listObjects(String prefix, String continuationToken, int maxKeys) { return delegate.listObjects(prefix, continuationToken, maxKeys); }
        @Override public void deleteObject(String key) {
            if (failDelete) throw new IllegalStateException("simulated delete outage");
            delegate.deleteObject(key);
        }
    }

    /** presign + stage + complete through the lifecycle service; returns the logical key. */
    private String completeFile(JdbcTemplate app, FileLifecycleService files, BucketBackedStorage objects, long owner, String name) {
        var upload = files.presign(owner, UploadBiz.FOODS, name, "image/png", (long) PNG.length);
        String staging = app.queryForObject("SELECT staging_key FROM eatwhat_file.upload_intent WHERE logical_key=?", String.class, upload.key());
        objects.stage(staging, pngWithSuffix(name));
        // Same size as declared: pad nothing, the bytes are PNG-length by construction.
        files.complete(owner, upload.key());
        return upload.key();
    }

    private static byte[] pngWithSuffix(String name) {
        byte[] bytes = PNG.clone();
        bytes[bytes.length - 1] = (byte) name.hashCode(); // distinct content per file, same length and PNG signature
        return bytes;
    }

    private static UUID seedIntent(JdbcTemplate admin, long owner, String logicalKey, String stagingKey, String status, String expiresExpr) {
        UUID intentId = UUID.randomUUID();
        admin.update("INSERT INTO eatwhat_file.upload_intent(intent_id, owner_id, owner_gid, biz, logical_key, staging_key, declared_size, declared_type, expires_at, status)"
                        + " VALUES (?,?,?,'foods',?,?,10,?," + expiresExpr + ",?)",
                intentId, owner, UUID.randomUUID(), logicalKey, stagingKey, CONTENT_TYPE, status);
        return intentId;
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static void put(S3ReplicaObjectStore target, String key, int size) throws Exception {
        Path file = Files.createTempFile("gcr-", ".bin");
        try {
            Files.write(file, new byte[size]);
            target.putFile(key, CONTENT_TYPE, file, SHA);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** In-memory staging; immutable PUTs go to the real bucket so the GC can delete them. */
    private static final class BucketBackedStorage implements FileObjectStorage {
        private final Map<String, byte[]> staging = new ConcurrentHashMap<>();
        private final S3ReplicaObjectStore bucket;

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
        }
    }
}
