package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import you.v50to.eatwhat.config.FileGcProperties;
import you.v50to.eatwhat.service.storage.replication.FileInventoryService;
import you.v50to.eatwhat.service.storage.replication.FileInventoryService.InventoryResult;
import you.v50to.eatwhat.service.storage.replication.InventoryObjectStore;
import you.v50to.eatwhat.service.storage.replication.S3ReplicaObjectStore;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Report-only inventory against Postgres and RustFS (local and peer buckets). */
class FileInventoryServiceIT {
    private static final String REGION = "us-east-1";
    private static final String CONTENT_TYPE = "application/octet-stream";
    private static final String SHA = "a".repeat(64);

    private static GenericContainer<?> postgres;
    private static GenericContainer<?> rustfs;
    private static String postgresUrlBase;
    private static String endpoint;

    private JdbcTemplate jdbc;
    private S3ReplicaObjectStore localStore;
    private S3ReplicaObjectStore peerStore;
    private FileGcProperties props;
    private FileInventoryService service;

    @BeforeAll
    static void start() {
        postgres = IsolatedContainers.postgres("inv-test");
        postgres.start();
        postgresUrlBase = IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE);
        rustfs = IsolatedContainers.rustfs("inv-test");
        rustfs.start();
        endpoint = "http://" + rustfs.getHost() + ":" + rustfs.getMappedPort(9000);
    }

    @AfterAll
    static void stop() {
        if (rustfs != null) rustfs.stop();
        if (postgres != null) postgres.stop();
    }

    /** Fresh migrated database and fresh buckets per test: no cross-test findings. */
    @BeforeEach
    void fresh() {
        String database = "it_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(postgresUrlBase, IsolatedContainers.USER, IsolatedContainers.PASSWORD))
                .execute("CREATE DATABASE " + database + " TEMPLATE template0");
        String url = IsolatedContainers.jdbcUrl(postgres, database);
        Flyway.configure().dataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true).load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD));

        String localBucket = "local-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String peerBucket = "peer-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try (S3Client admin = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build()) {
            admin.createBucket(CreateBucketRequest.builder().bucket(localBucket).build());
            admin.createBucket(CreateBucketRequest.builder().bucket(peerBucket).build());
        }
        localStore = new S3ReplicaObjectStore(endpoint, REGION, localBucket,
                IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY, true);
        peerStore = new S3ReplicaObjectStore(endpoint, REGION, peerBucket,
                IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY, true);

        props = new FileGcProperties();
        props.setInventoryEnabled(true);
        props.setSafetyWindow(Duration.ofSeconds(1));
        service = new FileInventoryService(jdbc, props, () -> localStore, () -> peerStore);
    }

    @AfterEach
    void close() {
        localStore.close();
        peerStore.close();
    }

    // ---- store -> DB ----

    @Test
    void unknownVerifiedIsReportedOnBothSitesAndNeverDeleted() throws Exception {
        String key = "verified/" + uuid();
        put(localStore, key, 10);
        put(peerStore, key, 10);
        ageObjects();

        InventoryResult local = service.runInventory("LOCAL");
        InventoryResult peer = service.runInventory("PEER");

        assertEquals(1L, local.findings().get("UNKNOWN_VERIFIED"));
        assertEquals(1L, peer.findings().get("UNKNOWN_VERIFIED"));
        assertEquals(List.of("UNKNOWN_VERIFIED"), kinds(local.runId()));
        assertEquals("PEER", jdbc.queryForObject("SELECT site FROM eatwhat_file.file_inventory_finding WHERE run_id=?", String.class, peer.runId()));
        assertTrue(localStore.head(key).isPresent());
        assertTrue(peerStore.head(key).isPresent());
    }

    @Test
    void orphanCandidateIsDistinguishedFromUnknownVerified() throws Exception {
        String orphanKey = "verified/" + uuid();
        String unknownKey = "verified/" + uuid();
        put(localStore, orphanKey, 5);
        put(localStore, unknownKey, 5);
        seedOrphanCandidate(orphanKey, "ORPHANED");
        // A CLEANED candidate is no longer an open orphan.
        String cleanedKey = "verified/" + uuid();
        put(localStore, cleanedKey, 5);
        seedOrphanCandidate(cleanedKey, "CLEANED");
        ageObjects();

        InventoryResult result = service.runInventory("LOCAL");

        assertEquals("ORPHAN_CANDIDATE", findingKind(result.runId(), orphanKey));
        assertEquals("UNKNOWN_VERIFIED", findingKind(result.runId(), unknownKey));
        assertEquals("UNKNOWN_VERIFIED", findingKind(result.runId(), cleanedKey));
        assertTrue(localStore.head(orphanKey).isPresent());
    }

    @Test
    void staleStagingReportedForMissingOrExpiredIntentButNotForLiveIntent() throws Exception {
        String noIntent = "staging/" + uuid();
        String expired = "staging/" + uuid();
        String live = "staging/" + uuid();
        put(localStore, noIntent, 3);
        put(localStore, expired, 3);
        put(localStore, live, 3);
        long owner = insertUser();
        seedIntent(owner, expired, "now() - interval '1 hour'");
        seedIntent(owner, live, "now() + interval '1 hour'");
        ageObjects();

        InventoryResult result = service.runInventory("LOCAL");

        assertEquals("STALE_STAGING", findingKind(result.runId(), noIntent));
        assertEquals("STALE_STAGING", findingKind(result.runId(), expired));
        assertNull(findingKind(result.runId(), live));
        assertEquals(2L, result.findings().get("STALE_STAGING"));
        assertEquals(3, result.listed());
        assertTrue(localStore.head(noIntent).isPresent() && localStore.head(expired).isPresent());
    }

    @Test
    void sizeMismatchProducesFindingAndExactlyOneAlertAndNoStateChange() throws Exception {
        String key = "verified/" + uuid();
        UUID fileId = seedRevision(key, 100);
        put(localStore, key, 7);
        ageObjects();

        InventoryResult first = service.runInventory("LOCAL");
        InventoryResult second = service.runInventory("LOCAL");

        assertEquals("SIZE_MISMATCH", findingKind(first.runId(), key));
        assertEquals("SIZE_MISMATCH", findingKind(second.runId(), key));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM eatwhat_file.file_replica_alert WHERE file_id=? AND alert_type='SIZE_MISMATCH' AND site='LOCAL'",
                Integer.class, fileId), "repeated runs must not duplicate the alert");
        String message = jdbc.queryForObject("SELECT message FROM eatwhat_file.file_replica_alert WHERE file_id=?", String.class, fileId);
        assertTrue(message.contains("7") && message.contains("100"));
        assertEquals("VERIFIED", jdbc.queryForObject("SELECT state FROM eatwhat_file.file_revision WHERE file_id=?", String.class, fileId));
        assertTrue(localStore.head(key).isPresent());
    }

    @Test
    void legacyPrefixObjectsAreReportedAsLegacyKey() throws Exception {
        props.setLegacyPrefixes(List.of("uploads/"));
        String key = "uploads/" + uuid() + ".png";
        put(localStore, key, 4);
        ageObjects();

        InventoryResult result = service.runInventory("LOCAL");

        assertEquals("LEGACY_KEY", findingKind(result.runId(), key));
        assertTrue(localStore.head(key).isPresent());
    }

    @Test
    void youngObjectsAndYoungReplicasAreIgnored() throws Exception {
        props.setSafetyWindow(Duration.ofHours(1));
        put(localStore, "verified/" + uuid(), 4);
        put(localStore, "staging/" + uuid(), 4);
        String absent = "verified/" + uuid();
        UUID fileId = seedRevision(absent, 4);
        seedReplica(fileId, "LOCAL", "AVAILABLE_LOCAL", absent);

        InventoryResult result = service.runInventory("LOCAL");

        assertEquals(2, result.listed());
        assertEquals(0, result.totalFindings());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_inventory_finding", Integer.class));
    }

    @Test
    void pagingFindsEveryObject() throws Exception {
        props.setPageSize(2);
        for (int i = 0; i < 5; i++) put(localStore, "verified/" + uuid(), 3);
        ageObjects();

        InventoryResult result = service.runInventory("LOCAL");

        assertEquals(5, result.listed());
        assertEquals(5L, result.findings().get("UNKNOWN_VERIFIED"));
        assertEquals(5, jdbc.queryForObject("SELECT count(DISTINCT object_key) FROM eatwhat_file.file_inventory_finding WHERE run_id=?",
                Integer.class, result.runId()));
        assertEquals(5L, jdbc.queryForObject("SELECT listed_count FROM eatwhat_file.file_gc_run WHERE run_id=?", Long.class, result.runId()));
    }

    // ---- DB -> store ----

    @Test
    void missingObjectReportedOnlyForAbsentAvailableReplicas() throws Exception {
        props.setReplicaCheckBatch(2);
        String presentKey = "verified/" + uuid();
        UUID present = seedRevision(presentKey, 6);
        put(localStore, presentKey, 6);
        seedReplica(present, "LOCAL", "AVAILABLE_LOCAL", presentKey);
        List<String> absentKeys = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String key = "verified/" + uuid();
            absentKeys.add(key);
            seedReplica(seedRevision(key, 6), "LOCAL", "AVAILABLE_LOCAL", key);
        }
        // Not an available-state replica: ignored.
        String failedKey = "verified/" + uuid();
        seedReplica(seedRevision(failedKey, 6), "LOCAL", "FAILED", failedKey);
        // PEER_VERIFIED on the peer site is checked only by the PEER run.
        String peerAbsent = "verified/" + uuid();
        seedReplica(seedRevision(peerAbsent, 6), "PEER", "PEER_VERIFIED", peerAbsent);
        ageObjects();

        InventoryResult local = service.runInventory("LOCAL");
        InventoryResult peer = service.runInventory("PEER");

        for (String key : absentKeys) assertEquals("MISSING_OBJECT", findingKind(local.runId(), key));
        assertNull(findingKind(local.runId(), presentKey));
        assertNull(findingKind(local.runId(), failedKey));
        assertNull(findingKind(local.runId(), peerAbsent));
        assertEquals(3L, local.findings().get("MISSING_OBJECT"));
        assertEquals("MISSING_OBJECT", findingKind(peer.runId(), peerAbsent));
        assertEquals(1L, peer.findings().get("MISSING_OBJECT"));
    }

    // ---- run row, failure, append-only ----

    @Test
    void runRowIsFinishedWithCountsAndConfigSnapshot() throws Exception {
        put(localStore, "verified/" + uuid(), 3);
        put(localStore, "staging/" + uuid(), 3);
        ageObjects();

        InventoryResult result = service.runInventory("LOCAL");

        Map<String, Object> run = jdbc.queryForMap("SELECT * FROM eatwhat_file.file_gc_run WHERE run_id=?", result.runId());
        assertEquals("INVENTORY", run.get("mode"));
        assertEquals("LOCAL", run.get("site"));
        assertNotNull(run.get("finished_at"));
        assertEquals(2L, run.get("listed_count"));
        assertEquals(2L, run.get("finding_count"));
        assertEquals(0L, run.get("error_count"));
        assertNull(run.get("error"));
        assertEquals("500", jdbc.queryForObject("SELECT config_snapshot->>'pageSize' FROM eatwhat_file.file_gc_run WHERE run_id=?",
                String.class, result.runId()));
        assertEquals(0L, result.errors());
        assertNull(result.error());
    }

    @Test
    void listingFailureMarksRunFailedAndRecordsError() {
        InventoryObjectStore failing = new DelegatingStore(localStore) {
            @Override public ObjectPage listObjects(String prefix, String token, int max) {
                throw new IllegalStateException("simulated listing outage");
            }
        };
        FileInventoryService failingService = new FileInventoryService(jdbc, props, () -> failing, () -> peerStore);

        InventoryResult result = failingService.runInventory("LOCAL");

        assertNotNull(result.error());
        assertTrue(result.error().contains("simulated listing outage"));
        Map<String, Object> run = jdbc.queryForMap("SELECT * FROM eatwhat_file.file_gc_run WHERE run_id=?", result.runId());
        assertNotNull(run.get("finished_at"));
        assertNotNull(run.get("error"));
        assertEquals(1L, run.get("error_count"));
    }

    @Test
    void unavailablePeerStoreIsSkippedWithoutARunRow() {
        FileInventoryService noPeer = new FileInventoryService(jdbc, props, () -> localStore, () -> null);

        InventoryResult result = noPeer.runInventory("PEER");

        assertNull(result.runId());
        assertEquals(0, result.listed());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_gc_run", Integer.class));
    }

    @Test
    void findingsAndRunsAreAppendOnlyAndRetained() throws Exception {
        put(localStore, "verified/" + uuid(), 3);
        ageObjects();
        InventoryResult result = service.runInventory("LOCAL");
        assertEquals(1, result.totalFindings());

        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE eatwhat_file.file_inventory_finding SET detail='x'"));
        assertThrows(DataAccessException.class, () -> jdbc.update("DELETE FROM eatwhat_file.file_inventory_finding"));
        assertThrows(DataAccessException.class, () -> jdbc.update("DELETE FROM eatwhat_file.file_gc_run"));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE eatwhat_file.file_gc_run SET site='PEER'"));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE eatwhat_file.file_gc_run SET config_snapshot='{}'::jsonb"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_inventory_finding", Integer.class));
    }

    // ---- hard guarantee ----

    @Test
    void inventoryNeverMutatesObjectsOrReplicaJobOrRevisionState() throws Exception {
        props.setLegacyPrefixes(List.of("uploads/"));
        props.setPageSize(2);
        props.setReplicaCheckBatch(1);

        // Unknown, orphan, size mismatch, missing replica, stale staging, legacy, healthy.
        put(localStore, "verified/" + uuid(), 3);
        put(peerStore, "verified/" + uuid(), 3);
        String orphan = "verified/" + uuid();
        put(localStore, orphan, 3);
        seedOrphanCandidate(orphan, "WRITTEN");
        String mismatch = "verified/" + uuid();
        UUID mismatchFile = seedRevision(mismatch, 50);
        seedReplica(mismatchFile, "LOCAL", "AVAILABLE_LOCAL", mismatch);
        put(localStore, mismatch, 3);
        String missing = "verified/" + uuid();
        UUID missingFile = seedRevision(missing, 3);
        seedReplica(missingFile, "LOCAL", "AVAILABLE_LOCAL", missing);
        jdbc.update("INSERT INTO eatwhat_file.file_transfer_job(job_id, file_id, revision, target_site, kind) VALUES (?,?,1,'PEER','COPY_TO_PEER')",
                UUID.randomUUID(), missingFile);
        put(localStore, "staging/" + uuid(), 3);
        put(localStore, "uploads/" + uuid(), 3);
        String healthy = "verified/" + uuid();
        UUID healthyFile = seedRevision(healthy, 3);
        seedReplica(healthyFile, "LOCAL", "AVAILABLE_LOCAL", healthy);
        put(localStore, healthy, 3);
        ageObjects();

        Map<String, String> objectsBefore = snapshotObjects();
        Map<String, String> dbBefore = snapshotTables();

        InventoryResult local = service.runInventory("LOCAL");
        InventoryResult peer = service.runInventory("PEER");

        assertTrue(local.totalFindings() >= 5, "run must actually have produced findings: " + local.findings());
        assertTrue(peer.totalFindings() >= 1);
        assertEquals(objectsBefore, snapshotObjects(), "no object may be created, changed or deleted");
        assertEquals(dbBefore, snapshotTables(), "replica, transfer job and revision rows must be untouched");
    }

    // ---- helpers ----

    private Map<String, String> snapshotObjects() {
        Map<String, String> snapshot = new TreeMap<>();
        for (var entry : Map.of("local", localStore, "peer", peerStore).entrySet()) {
            String token = null;
            do {
                var page = entry.getValue().listObjects("", token, 3);
                page.entries().forEach(e -> snapshot.put(entry.getKey() + ":" + e.key(), e.size() + "@" + e.lastModified()));
                token = page.nextToken();
            } while (token != null);
        }
        return snapshot;
    }

    private Map<String, String> snapshotTables() {
        Map<String, String> snapshot = new TreeMap<>();
        for (String table : List.of("file_replica", "file_transfer_job", "file_revision", "file_publication", "file_orphan_candidate", "upload_intent")) {
            snapshot.put(table, jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text), '[]'::jsonb)::text FROM eatwhat_file."
                    + table + " t", String.class));
        }
        return snapshot;
    }

    private List<String> kinds(UUID runId) {
        return jdbc.queryForList("SELECT kind FROM eatwhat_file.file_inventory_finding WHERE run_id=? ORDER BY finding_id", String.class, runId);
    }

    private String findingKind(UUID runId, String key) {
        List<String> found = jdbc.queryForList("SELECT kind FROM eatwhat_file.file_inventory_finding WHERE run_id=? AND object_key=?",
                String.class, runId, key);
        assertTrue(found.size() <= 1, "at most one finding per key per run");
        return found.isEmpty() ? null : found.get(0);
    }

    /** Objects must be older than the 1 second safety window by DB clock. */
    private static void ageObjects() throws InterruptedException {
        Thread.sleep(2500);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static void put(S3ReplicaObjectStore store, String key, int size) throws Exception {
        Path file = Files.createTempFile("inv-", ".bin");
        try {
            Files.write(file, new byte[size]);
            store.putFile(key, CONTENT_TYPE, file, SHA);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private UUID seedRevision(String finalKey, long size) {
        UUID fileId = UUID.randomUUID();
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key) VALUES (?,?, 'foods', ?)",
                fileId, UUID.randomUUID(), "foods/" + fileId);
        jdbc.update("""
                INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at)
                VALUES (?,1,?,?,?,?,'VERIFIED', clock_timestamp())
                """, fileId, finalKey, SHA, size, CONTENT_TYPE);
        return fileId;
    }

    private void seedReplica(UUID fileId, String site, String state, String key) {
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state) VALUES (?,1,?,?,?)",
                fileId, site, key, state);
    }

    private long insertUser() {
        return jdbc.queryForObject("INSERT INTO users(nick_name,password_hash) VALUES (?, 'synthetic-not-a-password') RETURNING id",
                Long.class, "u" + uuid().substring(0, 20));
    }

    private UUID seedIntent(long owner, String stagingKey, String expiresExpression) {
        UUID intentId = UUID.randomUUID();
        jdbc.update("INSERT INTO eatwhat_file.upload_intent(intent_id, owner_id, owner_gid, biz, logical_key, staging_key, declared_size, declared_type, expires_at)"
                        + " VALUES (?,?,?,'foods',?,?,10,?," + expiresExpression + ")",
                intentId, owner, UUID.randomUUID(), "foods/" + intentId, stagingKey, CONTENT_TYPE);
        return intentId;
    }

    private void seedOrphanCandidate(String objectKey, String state) {
        long owner = insertUser();
        UUID intentId = seedIntent(owner, "staging/" + uuid(), "now() + interval '1 hour'");
        jdbc.update("INSERT INTO eatwhat_file.file_orphan_candidate(candidate_id, intent_id, fencing_token, object_key, state) VALUES (?,?,1,?,?)",
                UUID.randomUUID(), intentId, objectKey, state);
    }

    /** Delegates every operation to a real store; tests override only listObjects. */
    private abstract static class DelegatingStore implements InventoryObjectStore {
        private final InventoryObjectStore delegate;

        DelegatingStore(InventoryObjectStore delegate) { this.delegate = delegate; }

        @Override public Optional<ObjectMeta> head(String key) { return delegate.head(key); }
        @Override public DigestResult downloadTo(String key, Path tmp, long maxBytes) { return delegate.downloadTo(key, tmp, maxBytes); }
        @Override public void putFile(String key, String contentType, Path src, String sha256Hex) { delegate.putFile(key, contentType, src, sha256Hex); }
        @Override public DigestResult readbackDigest(String key, long maxBytes) { return delegate.readbackDigest(key, maxBytes); }
    }
}
