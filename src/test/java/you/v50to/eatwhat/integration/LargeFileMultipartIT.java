package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.MultipartUpload;
import you.v50to.eatwhat.config.FileGcProperties;
import you.v50to.eatwhat.config.FileReplicationProperties;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.service.storage.replication.ByteRateLimiter;
import you.v50to.eatwhat.service.storage.replication.DeletableObjectStore;
import you.v50to.eatwhat.service.storage.replication.FileGcService;
import you.v50to.eatwhat.service.storage.replication.FileInventoryService;
import you.v50to.eatwhat.service.storage.replication.FilePublicationService;
import you.v50to.eatwhat.service.storage.replication.FileReplicaCatalog;
import you.v50to.eatwhat.service.storage.replication.FileReplicationWorker;
import you.v50to.eatwhat.service.storage.replication.FileReplicationWorker.Outcome;
import you.v50to.eatwhat.service.storage.replication.FileTransferJobStore;
import you.v50to.eatwhat.service.storage.replication.ReplicaObjectStore;
import you.v50to.eatwhat.service.storage.replication.ReplicationConsentGate;
import you.v50to.eatwhat.service.storage.replication.S3ReplicaObjectStore;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static you.v50to.eatwhat.integration.LargeFileTestSupport.MIB;

/**
 * Server-side multipart copy (stage 07E, T11) and the stale-multipart sweep, against real PostgreSQL and RustFS.
 * Parts are 5 MiB (the S3 minimum) so a 11-12 MiB object is a handful of parts and every test finishes in seconds.
 */
class LargeFileMultipartIT {
    private static final String REGION = "us-east-1";
    private static final String CONTENT_TYPE = "application/octet-stream";
    private static final long PART = 5 * MIB;

    private static GenericContainer<?> postgres;
    private static GenericContainer<?> rustfs;
    private static S3Client admin;
    private static String endpoint;
    private static String postgresUrlBase;

    @TempDir
    Path scratch;

    private JdbcTemplate jdbc;
    private DriverManagerDataSource dataSource;
    private FileTransferJobStore jobStore;
    private FileReplicationProperties props;
    private Path workerTemp;
    private String localBucket;
    private String peerBucket;
    private S3ReplicaObjectStore local;
    private S3ReplicaObjectStore peer;

    @BeforeAll
    static void start() {
        postgres = IsolatedContainers.postgres("lfm");
        postgres.start();
        postgresUrlBase = IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE);
        rustfs = IsolatedContainers.rustfs("lfm");
        rustfs.start();
        endpoint = "http://" + rustfs.getHost() + ":" + rustfs.getMappedPort(9000);
        admin = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @AfterAll
    static void stop() {
        if (admin != null) admin.close();
        if (rustfs != null) rustfs.stop();
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void freshEnvironment() throws Exception {
        String database = "it_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(postgresUrlBase, IsolatedContainers.USER, IsolatedContainers.PASSWORD))
                .execute("CREATE DATABASE " + database + " TEMPLATE template0");
        String url = IsolatedContainers.jdbcUrl(postgres, database);
        Flyway.configure().dataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true).load().migrate();
        dataSource = new DriverManagerDataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD);
        jdbc = new JdbcTemplate(dataSource);
        jobStore = new FileTransferJobStore(jdbc, new FilePublicationService(jdbc));

        localBucket = bucket("local");
        peerBucket = bucket("peer");
        local = store(localBucket, ByteRateLimiter.unlimited());
        peer = store(peerBucket, ByteRateLimiter.unlimited());

        workerTemp = Files.createDirectory(scratch.resolve("worker-temp"));
        props = new FileReplicationProperties();
        props.setTempDir(workerTemp.toString());
        props.setRetryBase(Duration.ofMillis(100));
        props.setRetryMax(Duration.ofSeconds(5));
        props.setJitter(0.0);
        props.setLeaseSeconds(120);
        props.setBatch(8);
        props.setTempReserveBytes(0);
    }

    @AfterEach
    void closeStores() {
        local.close();
        peer.close();
    }

    // ------------------------------------------------------------------------------------------------------
    // copy in both directions
    // ------------------------------------------------------------------------------------------------------

    @Test
    void copyToPeerUsesMultipartAndLeavesNothingBehind() throws Exception {
        copyAndVerify(true);
    }

    @Test
    void pullFromPeerUsesMultipartAndLeavesNothingBehind() throws Exception {
        copyAndVerify(false);
    }

    private void copyAndVerify(boolean toPeer) throws Exception {
        long size = 12 * MIB + 123; // 5 MiB parts: 5 + 5 + 2 MiB and a bit
        Seed seed = toPeer ? seedLocalSource(size, 11) : seedPeerSource(size, 12);
        S3ReplicaObjectStore destination = toPeer ? peer : local;
        String destinationBucket = toPeer ? peerBucket : localBucket;
        HookedStore hooked = new HookedStore(destination);
        Set<String> idsSeenOnTheJob = Collections.synchronizedSet(new LinkedHashSet<>());
        FileTransferJobStore.Job job = claimOne();
        hooked.hook = (part, total) -> {
            // The upload id is persisted on the job while the upload runs.
            idsSeenOnTheJob.add(jdbc.queryForObject(
                    "SELECT multipart_upload_id FROM eatwhat_file.file_transfer_job WHERE job_id=?", String.class, job.jobId()));
        };

        Outcome outcome = (toPeer ? worker(local, hooked) : worker(hooked, peer)).process(job);

        assertEquals(Outcome.COMPLETED, outcome);
        assertEquals(1, hooked.started.get(), "the object is above the multipart threshold");
        assertEquals(List.of(1, 2, 3), hooked.parts, "5 MiB parts of a 12 MiB object");
        assertEquals(3, hooked.totalParts.get());
        assertEquals(1, idsSeenOnTheJob.size());
        assertEquals(hooked.startedUploadId.get(), idsSeenOnTheJob.iterator().next(), "the job recorded the store's upload id");
        var digest = destination.readbackDigest(seed.key, size);
        assertEquals(size, digest.size());
        assertEquals(seed.sha, digest.sha256Hex(), "bytes are identical end to end");
        assertEquals(List.of(), openUploadIds(destinationBucket), "no multipart upload is left behind");
        assertEquals("DONE", jobStatus(job));
        assertNull(multipartColumn(job), "the upload id is cleared once the upload completed");
        try (var temp = Files.list(workerTemp)) {
            assertEquals(0, temp.count(), "the temp file is deleted");
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // threshold boundary and limits (store level)
    // ------------------------------------------------------------------------------------------------------

    @Test
    void belowTheThresholdIsOneSinglePutAndFromTheThresholdUpItIsMultipart() throws Exception {
        long[] sizes = {PART - 1, PART, PART + 1};
        boolean[] expectMultipart = {false, true, true};
        int[] expectParts = {0, 1, 2};
        for (int i = 0; i < sizes.length; i++) {
            var generated = LargeFileTestSupport.generate(scratch, "boundary", sizes[i], 100 + i);
            String key = "verified/boundary-" + i + "-" + uuid();
            RecordingListener listener = new RecordingListener();

            local.putFile(key, CONTENT_TYPE, generated.file(), generated.sha256(), listener);

            assertEquals(expectMultipart[i] ? 1 : 0, listener.started.get(), "size " + sizes[i]);
            assertEquals(expectParts[i], listener.parts.size(), "size " + sizes[i]);
            assertEquals(expectMultipart[i] ? 1 : 0, listener.beforeComplete.get(), "size " + sizes[i]);
            var digest = local.readbackDigest(key, sizes[i]);
            assertEquals(sizes[i], digest.size());
            assertEquals(generated.sha256(), digest.sha256Hex());
        }
        assertEquals(List.of(), openUploadIds(localBucket));
    }

    @Test
    void aFileNeedingMoreThanTenThousandPartsFailsFastBeforeAnythingIsCreated() throws Exception {
        Path huge = scratch.resolve("huge.sparse");
        try (RandomAccessFile file = new RandomAccessFile(huge.toFile(), "rw")) {
            file.setLength(PART * S3ReplicaObjectStore.MAX_PARTS + 1); // sparse: no real disk is used
        } catch (IOException unsupported) {
            assumeTrue(false, "the file system cannot create a 50 GB sparse file");
        }
        assumeTrue(Files.size(huge) == PART * S3ReplicaObjectStore.MAX_PARTS + 1);
        String key = "verified/huge-" + uuid();
        RecordingListener listener = new RecordingListener();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> local.putFile(key, CONTENT_TYPE, huge, "a".repeat(64), listener));

        assertTrue(failure.getMessage().contains("10000"), failure.getMessage());
        assertEquals(0, listener.started.get());
        assertTrue(local.head(key).isEmpty());
        assertEquals(List.of(), openUploadIds(localBucket));
    }

    @Test
    void theByteRateLimiterBoundsUploadThroughput() throws Exception {
        S3ReplicaObjectStore limited = store(localBucket, new ByteRateLimiter(4 * MIB));
        try {
            var generated = LargeFileTestSupport.generate(scratch, "rate", 12 * MIB, 77);
            String key = "verified/rate-" + uuid();
            long started = System.nanoTime();

            limited.putFile(key, CONTENT_TYPE, generated.file(), generated.sha256());

            double seconds = (System.nanoTime() - started) / 1e9;
            // 12 MiB at 4 MiB/s with a one-second (4 MiB) burst cannot take less than about 2 s. Coarse on purpose:
            // only a lower bound, the exact arithmetic is covered by ByteRateLimiterTest.
            assertTrue(seconds >= 1.7, "throughput was not throttled: " + seconds + " s");
            assertEquals(generated.sha256(), local.readbackDigest(key, 12 * MIB).sha256Hex());
        } finally {
            limited.close();
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // lease handling during the upload
    // ------------------------------------------------------------------------------------------------------

    @Test
    void aLostLeaseMidUploadAbortsTheUploadAndChangesNothingElse() throws Exception {
        props.setLeaseSeconds(3); // heartbeat interval: 1 s
        Seed seed = seedLocalSource(11 * MIB, 21);
        FileTransferJobStore.Job job = claimOne(); // the first lease extension (step 5) shortens it to 3 s
        AtomicReference<FileTransferJobStore.Job> newHolder = new AtomicReference<>();
        HookedStore hooked = new HookedStore(peer);
        hooked.hook = (part, total) -> {
            if (part == 2) {
                // The lease runs out and another worker reclaims the job while this one is still uploading.
                jdbc.update("UPDATE eatwhat_file.file_transfer_job SET lease_until=clock_timestamp()-interval '1 minute' WHERE job_id=?",
                        job.jobId());
                newHolder.set(jobStore.claim(1, Duration.ofSeconds(120)).get(0));
                Thread.sleep(1300); // past the heartbeat interval, so the next callback checks the lease
            }
        };

        Outcome outcome = worker(local, hooked).process(job);

        assertEquals(Outcome.LOST_LEASE, outcome);
        assertTrue(peer.head(seed.key).isEmpty(), "nothing was published at the destination");
        assertEquals(List.of(), openUploadIds(peerBucket), "the in-flight upload was aborted");
        assertEquals("LEASED", jobStatus(job), "the job still belongs to the new holder");
        assertEquals(job.fencingToken() + 1, jdbc.queryForObject(
                "SELECT fencing_token FROM eatwhat_file.file_transfer_job WHERE job_id=?", Long.class, job.jobId()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_replica WHERE file_id=? AND site='PEER'",
                Integer.class, seed.fileId), "no replica was recorded by the lost holder");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_replica_alert WHERE file_id=?",
                Integer.class, seed.fileId), "the lost holder raised no alert");
        try (var temp = Files.list(workerTemp)) {
            assertEquals(0, temp.count());
        }

        // The new holder finishes the copy normally and the upload id is cleared.
        Outcome second = worker(local, peer).process(newHolder.get());
        assertEquals(Outcome.COMPLETED, second);
        assertEquals(seed.sha, peer.readbackDigest(seed.key, seed.size).sha256Hex());
        assertEquals("DONE", jobStatus(job));
        assertNull(multipartColumn(job));
        assertEquals(List.of(), openUploadIds(peerBucket));
    }

    @Test
    void theHeartbeatKeepsAShortLeaseAliveAcrossASlowUpload() throws Exception {
        props.setLeaseSeconds(3); // each extension grants 3 s; the whole upload takes about 5 s
        Seed seed = seedLocalSource(11 * MIB, 31);
        FileTransferJobStore.Job job = claimOne(); // the first lease extension (step 5) shortens it to 3 s
        AtomicReference<List<FileTransferJobStore.Job>> stolen = new AtomicReference<>();
        HookedStore hooked = new HookedStore(peer);
        hooked.hook = (part, total) -> {
            Thread.sleep(1600); // a slow part: the original 3 s lease alone would have expired by part 2
            if (part == 2) {
                stolen.set(jobStore.claim(1, Duration.ofSeconds(120)));
            }
        };

        Outcome outcome = worker(local, hooked).process(job);

        assertEquals(Outcome.COMPLETED, outcome);
        assertNotNull(stolen.get());
        assertEquals(List.of(), stolen.get(), "another worker cannot claim the job while the heartbeat keeps its lease");
        assertEquals(seed.sha, peer.readbackDigest(seed.key, seed.size).sha256Hex());
        assertEquals("DONE", jobStatus(job));
    }

    // ------------------------------------------------------------------------------------------------------
    // failures: nothing partial survives, nothing is overwritten
    // ------------------------------------------------------------------------------------------------------

    @Test
    void aFailingPartAbortsTheUploadAndALaterRetrySucceeds() throws Exception {
        Seed seed = seedLocalSource(11 * MIB, 41);
        FileTransferJobStore.Job job = claimOne();
        AtomicBoolean fail = new AtomicBoolean(true);
        HookedStore hooked = new HookedStore(peer);
        hooked.hook = (part, total) -> {
            if (part == 2 && fail.getAndSet(false)) {
                throw SdkClientException.create("injected part failure");
            }
        };

        assertEquals(Outcome.RETRY, worker(local, hooked).process(job));

        assertTrue(peer.head(seed.key).isEmpty());
        assertEquals(List.of(), openUploadIds(peerBucket), "the failed upload was aborted");
        assertEquals("READY", jobStatus(job));
        assertEquals("TRANSIENT", jdbc.queryForObject(
                "SELECT last_error FROM eatwhat_file.file_transfer_job WHERE job_id=?", String.class, job.jobId()));
        assertNull(multipartColumn(job), "the upload id is cleared after the abort");

        jdbc.update("UPDATE eatwhat_file.file_transfer_job SET next_attempt_at=clock_timestamp() WHERE job_id=?", job.jobId());
        FileTransferJobStore.Job again = claimOne();
        assertEquals(Outcome.COMPLETED, worker(local, hooked).process(again));
        assertEquals(seed.sha, peer.readbackDigest(seed.key, seed.size).sha256Hex());
        assertEquals("DONE", jobStatus(job));
        assertEquals(List.of(), openUploadIds(peerBucket));
    }

    @Test
    void aDestinationThatAppearsMidUploadIsNeverOverwrittenAndTheUploadIsAborted() throws Exception {
        Seed seed = seedLocalSource(11 * MIB, 51);
        FileTransferJobStore.Job job = claimOne();
        var other = LargeFileTestSupport.generate(scratch, "other", 1024, 52);
        HookedStore hooked = new HookedStore(peer);
        hooked.hook = (part, total) -> {
            if (part == 2) {
                peer.putFile(seed.key, CONTENT_TYPE, other.file(), other.sha256()); // someone else writes the key
            }
        };

        assertEquals(Outcome.RETRY, worker(local, hooked).process(job));

        assertEquals(other.sha256(), peer.readbackDigest(seed.key, 11 * MIB).sha256Hex(), "the other writer's object is intact");
        assertEquals(List.of(), openUploadIds(peerBucket), "our upload was aborted instead of completed");
        assertEquals("READY", jobStatus(job));
        assertTrue(jdbc.queryForObject("SELECT last_error_detail FROM eatwhat_file.file_transfer_job WHERE job_id=?",
                String.class, job.jobId()).contains("Concurrent write"));

        // The next attempt sees a different object at the destination and quarantines instead of replacing it.
        jdbc.update("UPDATE eatwhat_file.file_transfer_job SET next_attempt_at=clock_timestamp() WHERE job_id=?", job.jobId());
        assertEquals(Outcome.QUARANTINED, worker(local, peer).process(claimOne()));
        assertEquals(other.sha256(), peer.readbackDigest(seed.key, 11 * MIB).sha256Hex());
    }

    // ------------------------------------------------------------------------------------------------------
    // disk space
    // ------------------------------------------------------------------------------------------------------

    @Test
    void tooLittleTempSpaceRetriesWithDiskSpaceAndNeverQuarantines() throws Exception {
        props.setTempReserveBytes(64 * MIB);
        long size = 2 * MIB;
        Seed seed = seedLocalSource(size, 61);
        FileTransferJobStore.Job job = claimOne();
        long needed = size + 64 * MIB;
        AtomicLong usable = new AtomicLong(needed - 1);
        List<Path> probed = new ArrayList<>();

        Outcome first = new FileReplicationWorker(jobStore, new AllowAll(), local, peer, props, new Random(1), path -> {
            probed.add(path);
            return usable.get();
        }).process(job);

        assertEquals(Outcome.RETRY, first);
        assertEquals(List.of(workerTemp), probed, "the configured temp directory was probed");
        assertEquals("READY", jobStatus(job));
        assertEquals("DISK_SPACE", jdbc.queryForObject(
                "SELECT last_error FROM eatwhat_file.file_transfer_job WHERE job_id=?", String.class, job.jobId()));
        assertTrue(peer.head(seed.key).isEmpty(), "nothing was downloaded or uploaded");
        try (var temp = Files.list(workerTemp)) {
            assertEquals(0, temp.count(), "no temp file was created");
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_replica_alert WHERE file_id=?",
                Integer.class, seed.fileId), "a transient space shortage raises no quarantine alert");

        // Exactly size + reserve is enough.
        usable.set(needed);
        jdbc.update("UPDATE eatwhat_file.file_transfer_job SET next_attempt_at=clock_timestamp() WHERE job_id=?", job.jobId());
        Outcome second = new FileReplicationWorker(jobStore, new AllowAll(), local, peer, props, new Random(1),
                path -> usable.get()).process(claimOne());
        assertEquals(Outcome.COMPLETED, second);
        assertEquals(seed.sha, peer.readbackDigest(seed.key, size).sha256Hex());
    }

    // ------------------------------------------------------------------------------------------------------
    // stale multipart uploads: inventory report and GC abort
    // ------------------------------------------------------------------------------------------------------

    @Test
    void staleMultipartUploadsAreReportedAndOnlyTheOldUnownedOnesAreAbortedBehindEveryGate() throws Exception {
        Duration age = Duration.ofSeconds(3);
        // Old unowned uploads (stale): A, A2, F (its job is QUARANTINED, so it owns nothing), X (claimed later, see below).
        String keyA = "verified/stale-a-" + uuid();
        String keyA2 = "verified/stale-a2-" + uuid();
        String keyF = "verified/stale-f-" + uuid();
        String keyX = "verified/stale-x-" + uuid();
        // Old but owned or out of scope: B (LEASED job), B2 (READY job), C (open upload intent), D (unmanaged prefix).
        String keyB = "verified/owned-leased-" + uuid();
        String keyB2 = "verified/owned-ready-" + uuid();
        String keyC = "staging/" + uuid() + "/owned-intent";
        String keyD = "legacy/old-" + uuid();
        String idA = createUpload(keyA);
        String idA2 = createUpload(keyA2);
        String idF = createUpload(keyF);
        String idX = createUpload(keyX);
        String idB = createUpload(keyB);
        String idB2 = createUpload(keyB2);
        String idC = createUpload(keyC);
        String idD = createUpload(keyD);
        jobWithUpload(idF, "QUARANTINED");
        jobWithUpload(idB, "LEASED");
        jobWithUpload(idB2, "READY");
        openIntent(keyC);
        Thread.sleep(4500); // everything above is now older than max(multipart-ttl, safety-window) = 3 s
        String keyE = "verified/young-" + uuid();
        String idE = createUpload(keyE); // young and unowned: must never be reported or aborted

        // ---- inventory: reports only the old, unowned, managed ones; aborts nothing ----
        FileGcProperties inventoryProps = gcProps(age);
        FileInventoryService inventory = new FileInventoryService(jdbc, inventoryProps, () -> local, () -> null);
        FileInventoryService.InventoryResult report = inventory.runInventory("LOCAL");
        assertNull(report.error(), String.valueOf(report.error()));
        assertEquals(4L, report.findings().get("STALE_MULTIPART"), report.findings().toString());
        assertEquals(new TreeSet<>(Set.of(keyA, keyA2, keyF, keyX)), new TreeSet<>(jdbc.queryForList(
                "SELECT object_key FROM eatwhat_file.file_inventory_finding WHERE kind='STALE_MULTIPART' AND run_id=?",
                String.class, report.runId())));
        assertEquals(8 + 1, openUploadIds(localBucket).size(), "the inventory never aborts anything");

        // ---- GC: every closed gate aborts nothing and does not even list the uploads ----
        SweepStore sweepStore = new SweepStore(local);
        SiteProperties campus = new SiteProperties();
        campus.setMode(SiteProperties.Mode.CAMPUS);
        for (String closed : List.of("dry-run", "execute-disabled", "no-classes", "other-class", "no-ack", "not-campus",
                "gc-disabled", "inventory-disabled")) {
            FileGcProperties gate = executeProps(age);
            SiteProperties site = campus;
            switch (closed) {
                case "dry-run" -> gate.setDryRun(true);
                case "execute-disabled" -> gate.setExecuteEnabled(false);
                case "no-classes" -> gate.setExecuteClasses(new LinkedHashSet<>());
                case "other-class" -> gate.setExecuteClasses(new LinkedHashSet<>(Set.of("STAGING")));
                case "no-ack" -> gate.setExecuteAck("");
                case "not-campus" -> site = new SiteProperties();
                case "gc-disabled" -> gate.setEnabled(false);
                case "inventory-disabled" -> gate.setInventoryEnabled(false);
                default -> throw new AssertionError(closed);
            }
            FileGcService gc = new FileGcService(jdbc, new DataSourceTransactionManager(dataSource), gate, site, () -> sweepStore);
            FileGcService.RunResult result = gc.run();
            assertEquals(0, result.multipartAborted(), closed);
            assertEquals(List.of(), sweepStore.aborted, "gate " + closed + " must not abort anything");
            assertEquals(0, sweepStore.lists.get(), "gate " + closed + " must not even list uploads");
        }
        assertEquals(9, openUploadIds(localBucket).size());

        // ---- a stale upload that a job claims AFTER the sweep listed it is re-checked right before its abort and survives ----
        sweepStore.afterList = new Runnable() {
            private boolean done;

            @Override
            public void run() {
                if (!done) {
                    done = true;
                    jobWithUpload(idX, "LEASED");
                }
            }
        };

        // ---- GC EXECUTE with the per-run cap: at most one abort per run (the candidates are A, A2 and F) ----
        FileGcProperties capped = executeProps(age);
        capped.setMaxDeletesPerRun(1);
        FileGcService cappedGc = new FileGcService(jdbc, new DataSourceTransactionManager(dataSource), capped, campus, () -> sweepStore);
        FileGcService.RunResult first = cappedGc.run();
        assertEquals(FileGcService.Mode.EXECUTE, first.mode());
        assertEquals(1, first.multipartAborted(), "the per-run cap bounds the aborts");
        assertEquals(0, first.errors(), String.valueOf(first.error()));
        assertEquals(1, cappedGc.run().multipartAborted());
        assertEquals(2, sweepStore.aborted.size());

        FileGcService gc = new FileGcService(jdbc, new DataSourceTransactionManager(dataSource), executeProps(age), campus, () -> sweepStore);
        FileGcService.RunResult last = gc.run();
        assertEquals(0, last.errors(), String.valueOf(last.error()));
        assertEquals(1, last.multipartAborted(), "the remaining stale upload");

        Set<String> abortedIds = new TreeSet<>(sweepStore.aborted);
        assertEquals(new TreeSet<>(Set.of(idA, idA2, idF)), abortedIds, "exactly the old, unowned uploads were aborted");
        assertEquals(new TreeSet<>(Set.of(idX, idB, idB2, idC, idD, idE)), new TreeSet<>(openUploadIds(localBucket)),
                "owned, unmanaged, young and late-claimed uploads survive");
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_inventory_finding WHERE kind='STALE_MULTIPART' AND detail LIKE 'ABORTED:%'",
                Integer.class));
        assertEquals(new TreeSet<>(Set.of("ABORTED:" + idA, "ABORTED:" + idA2, "ABORTED:" + idF)), new TreeSet<>(jdbc.queryForList(
                "SELECT detail FROM eatwhat_file.file_inventory_finding WHERE kind='STALE_MULTIPART' AND detail LIKE 'ABORTED:%'", String.class)));
        assertEquals(List.of(), sweepStore.deletes, "the sweep never deletes an object");
        // The "young" uploads may legitimately age past the (3 s) threshold while the previous runs executed, so a further
        // run is allowed to abort those; the invariant that must hold regardless of wall-clock timing is that uploads
        // which are owned by a LEASED/READY job (idX, idB, idB2) or an open intent (idC), or live under an unmanaged
        // prefix (idD), are NEVER aborted. (idE is unowned and simply ages, so it is not in this set.)
        gc.run();
        assertTrue(java.util.Collections.disjoint(sweepStore.aborted, Set.of(idX, idB, idB2, idC, idD)),
                "owned and unmanaged uploads are never aborted: " + sweepStore.aborted);
        assertEquals(List.of(), sweepStore.deletes, "the sweep never deletes an object");
    }

    // ------------------------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------------------------

    private record Seed(UUID fileId, String key, String sha, long size) { }

    private Seed seedLocalSource(long size, long randomSeed) throws Exception {
        var generated = LargeFileTestSupport.generate(scratch, "src", size, randomSeed);
        UUID fileId = UUID.randomUUID();
        String key = "verified/" + fileId.toString().replace("-", "");
        setupFileMetadata(fileId, key, generated.sha256(), size);
        local.putFile(key, CONTENT_TYPE, generated.file(), generated.sha256());
        jobStore.enqueue(fileId, 1, "COPY_TO_PEER", "PEER", null);
        Files.delete(generated.file());
        return new Seed(fileId, key, generated.sha256(), size);
    }

    private Seed seedPeerSource(long size, long randomSeed) throws Exception {
        var generated = LargeFileTestSupport.generate(scratch, "src", size, randomSeed);
        UUID fileId = UUID.randomUUID();
        String key = "verified/" + fileId.toString().replace("-", "");
        peer.putFile(key, CONTENT_TYPE, generated.file(), generated.sha256());
        new FileReplicaCatalog(jdbc).recordPeerRevision(new FileReplicaCatalog.PeerRevision(
                fileId, 1, UUID.randomUUID(), "test", "test/" + fileId, key, generated.sha256(), size, CONTENT_TYPE));
        Files.delete(generated.file());
        return new Seed(fileId, key, generated.sha256(), size);
    }

    private void setupFileMetadata(UUID fileId, String finalKey, String sha256, long size) {
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key) VALUES(?, ?, 'test', ?)",
                fileId, UUID.randomUUID(), "test/" + fileId);
        jdbc.update("""
                INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at)
                VALUES(?, 1, ?, ?, ?, ?, 'VERIFIED', clock_timestamp())
                """, fileId, finalKey, sha256, size, CONTENT_TYPE);
        jdbc.update("""
                INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at)
                VALUES(?, 1, 'LOCAL', ?, 'AVAILABLE_LOCAL', ?, ?, clock_timestamp())
                """, fileId, finalKey, sha256, size);
        jdbc.update("""
                INSERT INTO eatwhat_file.file_publication(file_id, revision, global_state, created_at, updated_at)
                VALUES(?, 1, 'LOCAL_ONLY', clock_timestamp(), clock_timestamp())
                ON CONFLICT DO NOTHING
                """, fileId);
    }

    private FileTransferJobStore.Job claimOne() {
        return claimOne(Duration.ofSeconds(120));
    }

    private FileTransferJobStore.Job claimOne(Duration lease) {
        List<FileTransferJobStore.Job> jobs = jobStore.claim(1, lease);
        assertEquals(1, jobs.size(), "a job must be claimable");
        return jobs.get(0);
    }

    private FileReplicationWorker worker(ReplicaObjectStore localLike, ReplicaObjectStore peerLike) {
        return new FileReplicationWorker(jobStore, new AllowAll(), localLike, peerLike, props, new Random(7));
    }

    private String jobStatus(FileTransferJobStore.Job job) {
        return jdbc.queryForObject("SELECT status FROM eatwhat_file.file_transfer_job WHERE job_id=?", String.class, job.jobId());
    }

    private String multipartColumn(FileTransferJobStore.Job job) {
        return jdbc.queryForObject("SELECT multipart_upload_id FROM eatwhat_file.file_transfer_job WHERE job_id=?",
                String.class, job.jobId());
    }

    private List<String> openUploadIds(String bucket) {
        return admin.listMultipartUploads(ListMultipartUploadsRequest.builder().bucket(bucket).build()).uploads().stream()
                .map(MultipartUpload::uploadId).toList();
    }

    private String createUpload(String key) {
        return admin.createMultipartUpload(b -> b.bucket(localBucket).key(key)).uploadId();
    }

    private void jobWithUpload(String uploadId, String status) {
        UUID fileId = UUID.randomUUID();
        String key = "verified/job-" + fileId.toString().replace("-", "");
        setupFileMetadata(fileId, key, "a".repeat(64), 10);
        String lease = "LEASED".equals(status) ? "clock_timestamp()+interval '1 hour'" : "NULL";
        jdbc.update("INSERT INTO eatwhat_file.file_transfer_job(job_id,file_id,revision,kind,target_site,status,attempts,"
                        + "next_attempt_at,fencing_token,lease_until,multipart_upload_id) "
                        + "VALUES (?,?,1,'COPY_TO_PEER','PEER',?,1,clock_timestamp(),1," + lease + ",?)",
                UUID.randomUUID(), fileId, status, uploadId);
    }

    private void openIntent(String stagingKey) {
        long owner = jdbc.queryForObject("INSERT INTO users(nick_name,password_hash) VALUES (?, 'synthetic') RETURNING id",
                Long.class, "lfm" + uuid().substring(0, 16));
        jdbc.update("INSERT INTO eatwhat_file.upload_intent(intent_id, owner_id, owner_gid, biz, logical_key, staging_key, "
                        + "declared_size, declared_type, expires_at, status) "
                        + "VALUES (?,?,?,'foods',?,?,10,?, clock_timestamp()+interval '1 hour','PENDING')",
                UUID.randomUUID(), owner, UUID.randomUUID(), "foods/" + uuid(), stagingKey, CONTENT_TYPE);
    }

    private static FileGcProperties gcProps(Duration age) {
        FileGcProperties gc = new FileGcProperties();
        gc.setEnabled(true);
        gc.setInventoryEnabled(true);
        gc.setSafetyWindow(age);
        gc.setMultipartTtl(age);
        return gc;
    }

    private static FileGcProperties executeProps(Duration age) {
        FileGcProperties gc = gcProps(age);
        gc.setDryRun(false);
        gc.setExecuteEnabled(true);
        gc.setExecuteClasses(new LinkedHashSet<>(Set.of("STALE_MULTIPART")));
        gc.setExecuteAck("drill-large-file-it");
        gc.setMaxDeletesPerRun(100);
        return gc;
    }

    private String bucket(String prefix) {
        String name = prefix + "-" + uuid().substring(0, 16);
        admin.createBucket(CreateBucketRequest.builder().bucket(name).build());
        return name;
    }

    private static S3ReplicaObjectStore store(String bucket, ByteRateLimiter limiter) {
        return new S3ReplicaObjectStore(endpoint, REGION, bucket, IsolatedContainers.RUSTFS_ACCESS_KEY,
                IsolatedContainers.RUSTFS_SECRET_KEY, true, new S3ReplicaObjectStore.Tuning(PART, PART, limiter));
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static final class AllowAll implements ReplicationConsentGate {
        @Override public boolean replicationAllowed(UUID ownerGid) { return true; }
        @Override public Long currentEpoch(UUID ownerGid) { return 1L; }
    }

    /** Records the multipart callbacks of a direct store call. */
    private static final class RecordingListener implements ReplicaObjectStore.UploadListener {
        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger beforeComplete = new AtomicInteger();
        final List<Integer> parts = new ArrayList<>();

        @Override public void onMultipartStarted(String uploadId) { started.incrementAndGet(); }
        @Override public void onPartUploaded(int partNumber, int totalParts) { parts.add(partNumber); }
        @Override public void onBeforeComplete() { beforeComplete.incrementAndGet(); }
    }

    @FunctionalInterface
    private interface PartHook {
        void onPart(int partNumber, int totalParts) throws Exception;
    }

    /** Wraps a store and runs a hook (which may sleep or throw) inside every part callback, before the worker's own. */
    private static final class HookedStore implements ReplicaObjectStore {
        private final S3ReplicaObjectStore delegate;
        volatile PartHook hook;
        final AtomicInteger started = new AtomicInteger();
        final AtomicReference<String> startedUploadId = new AtomicReference<>();
        final AtomicInteger totalParts = new AtomicInteger();
        final List<Integer> parts = Collections.synchronizedList(new ArrayList<>());

        HookedStore(S3ReplicaObjectStore delegate) {
            this.delegate = delegate;
        }

        @Override public Optional<ObjectMeta> head(String key) { return delegate.head(key); }
        @Override public DigestResult downloadTo(String key, Path tmp, long maxBytes) { return delegate.downloadTo(key, tmp, maxBytes); }
        @Override public DigestResult readbackDigest(String key, long maxBytes) { return delegate.readbackDigest(key, maxBytes); }

        @Override
        public void putFile(String key, String contentType, Path src, String sha256Hex) {
            delegate.putFile(key, contentType, src, sha256Hex);
        }

        @Override
        public void putFile(String key, String contentType, Path src, String sha256Hex, UploadListener listener) {
            delegate.putFile(key, contentType, src, sha256Hex, new UploadListener() {
                @Override
                public void onMultipartStarted(String uploadId) {
                    started.incrementAndGet();
                    startedUploadId.set(uploadId);
                    listener.onMultipartStarted(uploadId);
                }

                @Override
                public void onPartUploaded(int partNumber, int total) {
                    parts.add(partNumber);
                    totalParts.set(total);
                    PartHook current = hook;
                    if (current != null) {
                        try {
                            current.onPart(partNumber, total);
                        } catch (RuntimeException e) {
                            throw e;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    listener.onPartUploaded(partNumber, total);
                }

                @Override
                public void onBeforeComplete() {
                    listener.onBeforeComplete();
                }
            });
        }
    }

    /** A deletable store that records every list and abort (and any object delete) and can run a hook after a listing. */
    private static final class SweepStore implements DeletableObjectStore {
        private final S3ReplicaObjectStore delegate;
        final List<String> aborted = Collections.synchronizedList(new ArrayList<>());
        final List<String> deletes = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger lists = new AtomicInteger();
        volatile Runnable afterList;

        SweepStore(S3ReplicaObjectStore delegate) {
            this.delegate = delegate;
        }

        @Override public Optional<ObjectMeta> head(String key) { return delegate.head(key); }
        @Override public DigestResult downloadTo(String key, Path tmp, long maxBytes) { return delegate.downloadTo(key, tmp, maxBytes); }
        @Override public void putFile(String key, String contentType, Path src, String sha256Hex) { delegate.putFile(key, contentType, src, sha256Hex); }
        @Override public DigestResult readbackDigest(String key, long maxBytes) { return delegate.readbackDigest(key, maxBytes); }
        @Override public ObjectPage listObjects(String prefix, String token, int maxKeys) { return delegate.listObjects(prefix, token, maxKeys); }

        @Override
        public List<MultipartUploadEntry> listMultipartUploads(String prefix) {
            lists.incrementAndGet();
            List<MultipartUploadEntry> uploads = delegate.listMultipartUploads(prefix);
            Runnable hook = afterList;
            if (hook != null) {
                hook.run();
            }
            return uploads;
        }

        @Override
        public void abortMultipartUpload(String key, String uploadId) {
            aborted.add(uploadId);
            delegate.abortMultipartUpload(key, uploadId);
        }

        @Override
        public void deleteObject(String key) {
            deletes.add(key);
            delegate.deleteObject(key);
        }
    }
}
