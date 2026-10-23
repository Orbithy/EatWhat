package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import you.v50to.eatwhat.service.storage.FileAliasResolver;
import you.v50to.eatwhat.service.storage.replication.*;
import you.v50to.eatwhat.sync.EventCodec;
import you.v50to.eatwhat.sync.file.FileGcEvents;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit Maven opt-in; never selected by the normal unit/integration profiles. Missing real config FAILS. */
@TestMethodOrder(MethodOrderer.MethodName.class)
class LocalSitesRealStorageAcceptance {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID RUN = UUID.randomUUID();
    private static final boolean CONTRACT = Boolean.getBoolean("eatwhat.acceptance.local-contract");
    private static final Path ROOT = Path.of("target", "live-storage-acceptance", RUN.toString()).toAbsolutePath();
    private static final Map<String, Object> REPORT = new LinkedHashMap<>();
    private static final List<Map<String, Object>> CASES = new ArrayList<>();
    private static Path storageConfig;
    private static GenericContainer<?> contractStore;
    private static boolean foundationStarted;

    @BeforeAll static void start() throws Exception {
        Files.createDirectories(ROOT);
        REPORT.put("run_id", RUN.toString());
        REPORT.put("started_at", Instant.now().toString());
        REPORT.put("scope", CONTRACT ? "LOCAL_RUSTFS_CONTRACT_ONLY" : "LOCAL_SITES_REAL_R2_RUSTFS");
        REPORT.put("cross_region_partition", "DEFERRED");
        REPORT.put("business_http_api", "NOT_EXERCISED");
        REPORT.put("local_node_processes", 2);
        REPORT.put("retention_clock", "LOCAL_DB_FIXTURE_AGED_31_DAYS");
        REPORT.put("production_deployment", false);
        REPORT.put("cases", CASES);
        REPORT.put("status", "BLOCKED");
        REPORT.put("compiled_inputs_sha256", compiledFingerprint());
        persist();
        try {
            if (CONTRACT) storageConfig = contractStorage();
            else {
                String file = System.getProperty("eatwhat.acceptance.storage-config");
                if (file == null || !Files.isRegularFile(Path.of(file))) {
                    throw new IllegalArgumentException("A private real-storage configuration file is required");
                }
                storageConfig = Path.of(file).toAbsolutePath();
            }
            LiveStorageDrillSupport.validate(LiveStorageDrillSupport.read(storageConfig), CONTRACT);
            // Only after explicit configuration validation do we create any local environment.
            FileEventChannelIT.certs = ROOT.resolve("tls");
            foundationStarted = true;
            FileEventChannelIT.start();
            REPORT.put("status", "RUNNING");
            persist();
        } catch (Exception failure) {
            REPORT.put("setup_failure", failure.getClass().getSimpleName());
            persist();
            throw new IllegalStateException("Acceptance setup blocked; supply the private storage configuration. No real-store PASS recorded.");
        }
    }

    @AfterAll static void stop() throws Exception {
        Exception failure = null;
        try { if (foundationStarted) FileEventChannelIT.stop(); }
        catch (Exception error) { failure = error; }
        try { if (contractStore != null) contractStore.close(); }
        catch (Exception error) { failure = error; }
        REPORT.put("infrastructure_teardown", failure == null ? "PASS" : "FAIL");
        REPORT.put("finished_at", Instant.now().toString());
        boolean unchanged = REPORT.get("compiled_inputs_sha256").equals(compiledFingerprint());
        REPORT.put("compiled_inputs_unchanged", unchanged);
        if ("RUNNING".equals(REPORT.get("status"))) {
            REPORT.put("status", unchanged && failure == null && CASES.size() == 12
                    && CASES.stream().allMatch(c -> "PASS".equals(c.get("status"))) ? "PASS" : "FAIL");
        }
        persist();
        if (failure != null) throw failure;
    }

    private static void persist() throws IOException {
        Files.writeString(ROOT.resolve("report.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(REPORT) + "\n");
    }

    private static String compiledFingerprint() throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (Path root : List.of(Path.of("target/classes"), Path.of("target/test-classes"))) {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".class") || p.toString().endsWith(".sql")).sorted().toList()) {
                    digest.update(path.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    digest.update((byte) 0); digest.update(Files.readAllBytes(path)); digest.update((byte) 0);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Path contractStorage() throws Exception {
        contractStore = IsolatedContainers.rustfs("live-harness-contract");
        contractStore.start();
        String endpoint = "http://" + contractStore.getHost() + ":" + contractStore.getMappedPort(9000);
        var properties = new Properties();
        properties.setProperty("acceptance.dedicated-test-buckets", "true");
        var credentials = new Properties();
        credentials.setProperty("access-key", IsolatedContainers.RUSTFS_ACCESS_KEY);
        credentials.setProperty("secret-key", IsolatedContainers.RUSTFS_SECRET_KEY);
        Path credentialFile = ROOT.resolve("synthetic.credentials.properties");
        save(credentials, credentialFile);
        try (var s3 = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        IsolatedContainers.RUSTFS_ACCESS_KEY, IsolatedContainers.RUSTFS_SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build()) {
            for (String name : List.of("rustfs", "r2")) {
                String bucket = "contract-" + name + "-" + RUN;
                s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                properties.setProperty(name + ".endpoint", endpoint);
                properties.setProperty(name + ".bucket", bucket);
                properties.setProperty(name + ".region", "us-east-1");
                properties.setProperty(name + ".path-style", "true");
                properties.setProperty(name + ".credentials-file", credentialFile.toString());
            }
        }
        Path config = ROOT.resolve("synthetic.storage.properties");
        save(properties, config);
        return config;
    }

    private static void save(Properties properties, Path file) throws IOException {
        try (var writer = Files.newBufferedWriter(file)) { properties.store(writer, "Test harness configuration"); }
    }

    @FunctionalInterface interface Drill { void run(Fixture fixture) throws Exception; }
    private void exercise(String name, boolean externalOrigin, boolean aged, Drill drill) throws Exception {
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("name", name);
        evidence.put("origin", externalOrigin ? "EXTERNAL" : "CAMPUS");
        evidence.put("started_at", Instant.now().toString());
        evidence.put("status", "RUNNING");
        CASES.add(evidence); persist();
        Fixture fixture = null;
        try {
            fixture = new Fixture(externalOrigin, evidence);
            fixture.initialize(aged);
            drill.run(fixture);
            fixture.assertCompleted();
            evidence.put("status", "PASS");
        } catch (Exception | AssertionError failure) {
            evidence.put("status", "FAIL");
            evidence.put("failure_class", failure.getClass().getSimpleName());
            Arrays.stream(failure.getStackTrace()).filter(s -> s.getClassName().contains("LocalSitesRealStorageAcceptance"))
                    .findFirst().ifPresent(s -> evidence.put("failure_site", s.getFileName() + ":" + s.getLineNumber()));
            throw failure;
        } finally {
            try {
                if (fixture != null) fixture.close();
            } catch (Exception cleanupFailure) {
                evidence.put("status", "FAIL");
                evidence.put("cleanup_failure", cleanupFailure.getClass().getSimpleName());
                throw cleanupFailure;
            } finally {
                evidence.put("finished_at", Instant.now().toString()); persist();
            }
        }
    }

    @ParameterizedTest(name = "fresh retention and restored references; externalOrigin={0}")
    @ValueSource(booleans = {false, true})
    void aFreshRetentionAndRestoredReferencesKeepBothRealObjects(boolean externalOrigin) throws Exception {
        exercise("fresh_retention_and_restore", externalOrigin, false, f -> {
            assertEquals(0, f.gc(false).path("deleted").asLong());
            assertEquals(0, f.gc(true).path("deleted").asLong());
            assertEquals(0, f.p.campus.count("eatwhat_file.file_gc_coordination"));
            f.command(false, "restore"); f.command(true, "restore"); f.exchange();
            assertEquals(0, f.gc(false).path("deleted").asLong());
            f.assertBothBytes(); f.assertNoDeleteCalls();
            f.command(false, "release"); f.command(true, "release"); f.exchange();
            assertEquals(0, f.gc(false).path("deleted").asLong());
            f.age(false); f.age(true); f.prepare(); f.commit();
        });
    }

    @ParameterizedTest(name = "peer restore before prepare; externalOrigin={0}")
    @ValueSource(booleans = {false, true})
    void bPeerRestoreWhileRequestedBlocksPreparationAndResetsRetention(boolean externalOrigin) throws Exception {
        exercise("restore_before_peer_prepare", externalOrigin, true, f -> {
            f.gc(false); f.exchange(); assertEquals("REQUESTED", f.state(true));
            f.command(true, "restore"); f.exchange();
            assertEquals(0, f.gc(true).path("deleted").asLong());
            assertEquals("REQUESTED", f.state(true));
            assertEquals(0, f.gc(false).path("deleted").asLong());
            f.assertBothBytes(); f.assertNoDeleteCalls();
            f.command(true, "release"); f.exchange();
            assertEquals(0, f.gc(true).path("deleted").asLong());
            assertEquals("REQUESTED", f.state(true));
            f.age(true); f.gc(true); assertEquals("PREPARED", f.state(true)); f.exchange(); f.commit();
        });
    }

    @ParameterizedTest(name = "prepared fence; externalOrigin={0}")
    @ValueSource(booleans = {false, true})
    void cPreparationRejectsRestoreNewReferenceAndAvatarProjection(boolean externalOrigin) throws Exception {
        exercise("prepare_before_restore", externalOrigin, true, f -> {
            f.prepare();
            for (boolean external : List.of(false, true)) {
                for (String command : List.of("restore", "new-reference", "avatar")) {
                    JsonNode response = f.node(external).request(command, f.file.finalKey());
                    assertFalse(response.path("ok").asBoolean());
                    assertEquals("23514", response.path("sqlstate").asText());
                }
            }
            f.assertBothBytes(); f.assertNoDeleteCalls(); f.commit();
        });
    }

    @ParameterizedTest(name = "restore holds version lock; externalOrigin={0}")
    @ValueSource(booleans = {false, true})
    void dConcurrentRestorationWinsBeforeGcVersionLock(boolean externalOrigin) throws Exception {
        exercise("concurrent_restore_first", externalOrigin, true, f -> {
            try (Connection connection = f.p.campus.db.dataSource().getConnection()) {
                connection.setAutoCommit(false);
                try {
                    try (var lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?::text||':1',0))")) {
                        lock.setString(1, f.file.fileId().toString()); lock.execute();
                    }
                    try (var restore = connection.prepareStatement("INSERT INTO eatwhat_file.file_reference_event(logical_key,ref_kind,ref_id,action,reason) VALUES(?,'test','acceptance','RESTORE','ADMIN')")) {
                        restore.setString(1, f.file.logicalKey()); restore.executeUpdate();
                    }
                    assertTrue(f.gc(false).path("skipped").asLong() > 0);
                    assertEquals(0, f.p.campus.count("eatwhat_file.file_gc_coordination"));
                    connection.commit();
                } finally { connection.rollback(); }
            }
            assertEquals(0, f.gc(false).path("deleted").asLong());
            f.assertBothBytes(); f.assertNoDeleteCalls();
            f.command(false, "release"); f.exchange();
            assertEquals(0, f.gc(false).path("deleted").asLong());
            f.age(false); f.prepare(); f.commit();
        });
    }

    @ParameterizedTest(name = "real delete with lost response and JVM restart; externalOrigin={0}")
    @ValueSource(booleans = {false, true})
    void eDeleteResponseLossAndNodeRestartResumeSameDurableOperation(boolean externalOrigin) throws Exception {
        exercise("delete_response_loss_with_successful_probe", externalOrigin, true, f -> {
            f.prepare();
            UUID operation = f.operation();
            f.campus.call("lose-delete-response", "");
            assertEquals(1, f.gc(false).path("deleted").asLong());
            assertTrue(f.campusStore.head(f.file.finalKey()).isEmpty());
            assertTrue(f.externalStore.head(f.file.finalKey()).isPresent());
            assertEquals("DELETED", f.state(false));
            f.restartCampus();
            assertEquals(operation, f.operation()); f.exchange();
            assertEquals("COMMITTED", f.state(true));
            assertEquals(1, f.gc(true).path("deleted").asLong()); f.exchange();
        });
    }

    @ParameterizedTest(name = "real delete with lost response AND unavailable confirmation; externalOrigin={0}")
    @ValueSource(booleans = {false, true})
    void fDeleteResponseAndConfirmationLossRemainFencedUntilRestart(boolean externalOrigin) throws Exception {
        exercise("delete_response_loss_and_restart", externalOrigin, true, f -> {
            f.prepare();
            UUID operation = f.operation();
            f.campus.call("lose-delete-and-probe", "");
            assertEquals(1, f.gcWithExpectedError(false).path("errors").asLong());
            assertTrue(f.campusStore.head(f.file.finalKey()).isEmpty());
            assertTrue(f.externalStore.head(f.file.finalKey()).isPresent());
            assertEquals("DELETING", f.p.campus.sql().queryForObject("SELECT state FROM eatwhat_file.file_replica WHERE file_id=? AND site='LOCAL'", String.class, f.file.fileId()));
            f.restartCampus();
            assertEquals(operation, f.operation()); f.exchange();
            assertEquals("COMMITTED", f.state(true));
            assertEquals(1, f.gc(true).path("deleted").asLong()); f.exchange();
            assertEquals(1, f.gc(false).path("deleted").asLong()); f.exchange();
        });
    }

    private static final class Fixture implements AutoCloseable {
        final boolean externalOrigin;
        final Map<String, Object> evidence;
        final Path directory = ROOT.resolve(UUID.randomUUID().toString());
        FileEventChannelIT.Pair p;
        FileReplicaCatalog.PeerRevision file;
        LiveStorageDrillSupport.OwnedStore campusStore, externalStore;
        Child campus, external;
        boolean owned;
        Path keys;
        final List<Long> campusPids = new ArrayList<>();
        final List<Map<String, Object>> gcRuns = new ArrayList<>();

        Fixture(boolean externalOrigin, Map<String, Object> evidence) {
            this.externalOrigin = externalOrigin; this.evidence = evidence;
        }

        void initialize(boolean aged) throws Exception {
            Files.createDirectories(directory);
            p = new FileEventChannelIT.Pair();
            // Reuse the established schema/identity fixture, but all protocol work runs in the TWO CHILD JVMs.
            p.close();
            byte[] bytes = new byte[96 * 1024];
            new Random(RUN.getMostSignificantBits()).nextBytes(bytes);
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            file = p.seed(externalOrigin ? p.external : p.campus, "avatar", sha, bytes.length);
            var stores = LiveStorageDrillSupport.read(storageConfig);
            campusStore = new LiveStorageDrillSupport.OwnedStore(LiveStorageDrillSupport.open(stores, "rustfs"), Set.of(file.finalKey()));
            externalStore = new LiveStorageDrillSupport.OwnedStore(LiveStorageDrillSupport.open(stores, "r2"), Set.of(file.finalKey()));
            // A collision aborts before ownership is claimed and MUST NOT delete the existing key.
            assertTrue(campusStore.head(file.finalKey()).isEmpty());
            assertTrue(externalStore.head(file.finalKey()).isEmpty());
            owned = true;
            keys = directory.resolve("owned-keys.txt"); Files.writeString(keys, file.finalKey() + "\n");
            evidence.put("file_id", file.fileId().toString());
            evidence.put("revision", 1); evidence.put("sha256", sha); evidence.put("size", bytes.length);
            evidence.put("campus_instance", p.campus.node.instanceId().toString());
            evidence.put("external_instance", p.external.node.instanceId().toString());
            evidence.put("gc_runs", gcRuns);
            persist();
            Path body = directory.resolve("body.bin"); Files.write(body, bytes);
            var source = externalOrigin ? externalStore : campusStore;
            source.putFile(file.finalKey(), "image/png", body, sha);
            assertEquals(new ReplicaObjectStore.DigestResult(bytes.length, sha), source.readbackDigest(file.finalKey(), bytes.length));
            var origin = externalOrigin ? p.external : p.campus;
            origin.sql().update("UPDATE eatwhat_file.file_replica SET verified_sha256=?,verified_size=?,verified_at=clock_timestamp() WHERE file_id=? AND site='LOCAL'", sha, bytes.length, file.fileId());
            external = new Child(configuration(true, 0), directory);
            campus = new Child(configuration(false, external.port), directory); campusPids.add(campus.pid);
            assertNotEquals(campus.pid, external.pid);
            evidence.put("campus_pids", campusPids); evidence.put("external_pid", external.pid);
            if (!externalOrigin) command(false, "enqueue");
            exchange();
            var receiver = externalOrigin ? p.campus : p.external;
            assertNull(new FileAliasResolver(receiver.sql()).resolve(file.logicalKey()).readableObjectKey());
            assertEquals(1, campus.call("replicate", "").path("processed").asInt()); exchange();
            assertBothBytes();
            command(false, "reference"); command(true, "reference"); exchange();
            for (var side : List.of(p.campus, p.external)) {
                assertEquals("AVAILABLE_GLOBAL", side.sql().queryForObject("SELECT global_state FROM eatwhat_file.file_publication WHERE file_id=?", String.class, file.fileId()));
                assertNull(new FileAliasResolver(side.sql()).resolve(file.logicalKey()).readableObjectKey(),
                        "PRIVATE references must never publish a public download alias");
                assertEquals(2, side.sql().queryForObject("SELECT count(*) FROM eatwhat_file.file_replica WHERE file_id=? AND state IN ('AVAILABLE_LOCAL','PEER_VERIFIED') AND verified_sha256=? AND verified_size=?", Integer.class, file.fileId(), sha, bytes.length));
            }
            assertEquals("DONE", p.campus.sql().queryForObject("SELECT status FROM eatwhat_file.file_transfer_job WHERE file_id=?", String.class, file.fileId()));
            command(false, "release"); command(true, "release"); exchange();
            if (aged) { age(false); age(true); }
            evidence.put("byte_replication", "PASS"); persist();
        }

        Properties configuration(boolean externalSite, int externalPort) {
            var side = externalSite ? p.external : p.campus;
            var other = externalSite ? p.campus : p.external;
            var tls = FileEventChannelIT.tls;
            var settings = new Properties();
            settings.setProperty("db.url", side.db.url());
            settings.setProperty("db.runtime-user", side.db.runtimeUsername());
            settings.setProperty("site.id", side.node.siteId()); settings.setProperty("site.instance", side.node.instanceId().toString());
            settings.setProperty("site.mode", side.db.site().getMode().name());
            settings.setProperty("peer.id", other.node.siteId()); settings.setProperty("peer.instance", other.node.instanceId().toString());
            settings.setProperty("peer.origin", "https://127.0.0.1:" + externalPort);
            settings.setProperty("tls.key-store", (externalSite ? tls.external() : tls.campus()).keyStore().toString());
            settings.setProperty("tls.peer-pin", (externalSite ? tls.campus() : tls.external()).pin());
            settings.setProperty("tls.trust-store", tls.trustStore().toString());
            settings.setProperty("test.user", Long.toString(side.uid));
            settings.setProperty("owned.keys", keys.toString()); settings.setProperty("storage.config", storageConfig.toString());
            settings.setProperty("local.contract", Boolean.toString(CONTRACT));
            return settings;
        }

        Child node(boolean externalSite) { return externalSite ? external : campus; }
        JsonNode command(boolean externalSite, String command) throws Exception { return node(externalSite).call(command, file.finalKey()); }
        JsonNode gcWithExpectedError(boolean externalSite) throws Exception {
            JsonNode result = command(externalSite, "gc");
            gcRuns.add(Map.of("site", externalSite ? "EXTERNAL" : "CAMPUS", "time", Instant.now().toString(),
                    "deleted", result.path("deleted").asLong(), "errors", result.path("errors").asLong(),
                    "skipped", result.path("skipped").asLong(), "blocked", result.path("blocked")));
            persist();
            return result;
        }
        JsonNode gc(boolean externalSite) throws Exception {
            JsonNode result = gcWithExpectedError(externalSite);
            assertEquals(0, result.path("errors").asLong(), "No unexpected GC error is allowed to masquerade as a safe hold");
            return result;
        }
        void age(boolean externalSite) {
            // Only the newly-created local test DB clock is changed. No remote schema or retention is altered.
            (externalSite ? p.external : p.campus).db.admin().update("UPDATE eatwhat_file.file_tombstone SET unreferenced_since=clock_timestamp()-interval '31 days' WHERE file_id=?", file.fileId());
        }
        String state(boolean externalSite) {
            return (externalSite ? p.external : p.campus).sql().queryForObject("SELECT state FROM eatwhat_file.file_gc_coordination WHERE file_id=?", String.class, file.fileId());
        }
        UUID operation() { return p.campus.sql().queryForObject("SELECT operation_id FROM eatwhat_file.file_gc_coordination WHERE file_id=?", UUID.class, file.fileId()); }
        void exchange() throws Exception {
            int idleRounds = 0;
            for (int i = 0; i < 12; i++) {
                campus.call("publish", ""); external.call("publish", ""); campus.call("network", "");
                campus.call("drain", ""); external.call("drain", ""); Thread.sleep(25);
                if (pendingEvents() == 0 && i >= 2) {
                    if (++idleRounds == 2) return;
                } else idleRounds = 0;
            }
            assertEquals(0, pendingEvents(), "All envelopes, application receipts and reference publications must converge");
        }
        long pendingEvents() {
            long pending = 0;
            for (var side : List.of(p.campus, p.external)) {
                pending += side.sql().queryForObject("""
                        SELECT (SELECT count(*) FROM eatwhat_sync.delivery WHERE status<>'ACKED')
                          +(SELECT count(*) FROM eatwhat_sync.inbox WHERE status<>'APPLIED')
                          +(SELECT count(*) FROM eatwhat_file.file_sync_receipt WHERE NOT published)
                          +(SELECT count(*) FROM eatwhat_file.file_sync_dirty WHERE generation>published_generation)
                        """, Long.class);
            }
            return pending;
        }
        void prepare() throws Exception {
            assertEquals(0, gc(false).path("deleted").asLong()); assertEquals("PREPARING", state(false));
            exchange(); assertEquals("REQUESTED", state(true));
            assertEquals(0, gc(true).path("deleted").asLong()); assertEquals("PREPARED", state(true)); exchange();
            assertBothBytes(); assertNoDeleteCalls();
        }
        void commit() throws Exception {
            evidence.put("operation_id", operation().toString()); persist();
            assertEquals(1, gc(false).path("deleted").asLong());
            assertTrue(externalStore.head(file.finalKey()).isPresent());
            exchange(); assertEquals("COMMITTED", state(true));
            assertEquals(1, gc(true).path("deleted").asLong()); exchange();
        }
        void assertBothBytes() {
            var expected = new ReplicaObjectStore.DigestResult(file.sizeBytes(), file.sha256());
            assertEquals(expected, campusStore.readbackDigest(file.finalKey(), file.sizeBytes()));
            assertEquals(expected, externalStore.readbackDigest(file.finalKey(), file.sizeBytes()));
        }
        void assertNoDeleteCalls() throws Exception {
            assertEquals(0, campus.call("counters", "").path("local_delete_calls").asInt());
            assertEquals(0, external.call("counters", "").path("local_delete_calls").asInt());
        }
        void restartCampus() throws Exception {
            campus.close(); campus = new Child(configuration(false, external.port), directory); campusPids.add(campus.pid);
            evidence.put("operation_id", operation().toString()); persist();
        }
        void assertCompleted() throws Exception {
            assertTrue(campusStore.head(file.finalKey()).isEmpty());
            assertTrue(externalStore.head(file.finalKey()).isEmpty());
            for (var side : List.of(p.campus, p.external)) {
                assertEquals("DELETED", side.sql().queryForObject("SELECT state FROM eatwhat_file.file_gc_coordination WHERE file_id=?", String.class, file.fileId()));
                assertEquals(operation(), side.sql().queryForObject("SELECT operation_id FROM eatwhat_file.file_gc_coordination WHERE file_id=?", UUID.class, file.fileId()));
                assertTrue(side.sql().queryForObject("SELECT peer_deleted FROM eatwhat_file.file_gc_coordination WHERE file_id=?", Boolean.class, file.fileId()));
                assertEquals(2, side.sql().queryForObject("SELECT count(*) FROM eatwhat_file.file_replica WHERE file_id=? AND state='DELETED'", Integer.class, file.fileId()));
                assertEquals(0, side.sql().queryForObject("SELECT count(*) FROM eatwhat_sync.delivery WHERE status<>'ACKED'", Integer.class));
                var phases = side.sql().queryForList("SELECT envelope::text FROM eatwhat_sync.outbox WHERE aggregate_type='file.gc' ORDER BY local_order", String.class)
                        .stream().map(s -> FileGcEvents.decision(new EventCodec().decode(s).payloadJson()).phase()).toList();
                assertEquals(side == p.campus ? List.of("PREPARE", "COMMIT", "DELETED") : List.of("PREPARED", "DELETED"), phases);
                evidence.put(side == p.campus ? "campus_gc_phases" : "external_gc_phases", phases);
            }
            evidence.put("coordinated_deletion", "PASS");
            evidence.put("both_objects_absent_before_cleanup", true); persist();
        }
        @Override public void close() throws Exception {
            Exception failure = null;
            for (var child : Arrays.asList(campus, external)) if (child != null) {
                try { child.close(); } catch (Exception e) { failure = e; }
            }
            int cleanups = 0;
            for (var store : Arrays.asList(campusStore, externalStore)) if (store != null) {
                try {
                    if (owned && store.head(file.finalKey()).isPresent()) { store.deleteObject(file.finalKey()); cleanups++; }
                    if (owned && store.head(file.finalKey()).isPresent()) throw new IllegalStateException("ACCEPTANCE_CLEANUP_OBJECT_REMAINS");
                } catch (Exception e) { failure = e; }
                finally { store.close(); }
            }
            evidence.put("teardown_cleanup_deletes", cleanups);
            evidence.put("teardown", failure == null ? "PASS" : "FAIL");
            if (p != null) p.close();
            if (failure != null) throw failure;
        }
    }

    private static final class Child implements AutoCloseable {
        final Process process;
        final BufferedReader output;
        final BufferedWriter input;
        final ExecutorService reader = Executors.newSingleThreadExecutor();
        final Path log;
        final int port;
        final long pid;

        Child(Properties config, Path directory) throws Exception {
            Path configFile = directory.resolve("node-" + UUID.randomUUID() + ".properties"); save(config, configFile);
            log = directory.resolve("node-" + UUID.randomUUID() + ".log");
            String classpath = Arrays.stream(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")).split(File.pathSeparator))
                    .map(p -> Path.of(p).toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
            var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx256m", "-cp", classpath, LocalFileSiteProcess.class.getName(), configFile.toString());
            // Keep explicit local proxy exclusions consistent in the parent and both protocol JVMs.
            // In particular, a hosts override must not be bypassed by proxy-side DNS resolution.
            for (String property : List.of("http.nonProxyHosts", "https.nonProxyHosts")) {
                String value = System.getProperty(property);
                if (value != null && !value.isBlank()) builder.command().add(1, "-D" + property + "=" + value);
            }
            builder.environment().clear(); builder.environment().put("LANG", "C.UTF-8"); builder.environment().put("TZ", "UTC");
            builder.redirectError(log.toFile());
            process = builder.start();
            output = process.inputReader(); input = process.outputWriter();
            try {
                var ready = response();
                if (!ready.path("ok").asBoolean() || !ready.path("ready").asBoolean()) throw new IllegalStateException("Local node startup failed");
                port = ready.path("port").asInt(); pid = ready.path("pid").asLong();
            } catch (Exception failure) { close(); throw failure; }
        }
        JsonNode response() throws Exception {
            return reader.submit(() -> {
                String line;
                while ((line = output.readLine()) != null) {
                    if (line.startsWith(LocalFileSiteProcess.PREFIX)) return JSON.readTree(line.substring(LocalFileSiteProcess.PREFIX.length()));
                    Files.writeString(log, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
                throw new EOFException("Local drill node exited");
            }).get(90, TimeUnit.SECONDS);
        }
        JsonNode request(String command, String key) throws Exception {
            input.write(JSON.writeValueAsString(Map.of("command", command, "key", key))); input.newLine(); input.flush();
            return response();
        }
        JsonNode call(String command, String key) throws Exception {
            JsonNode response = request(command, key);
            if (!response.path("ok").asBoolean()) throw new IllegalStateException("Local drill command failed: " + command + " (" + response.path("error").asText() + ")");
            return response;
        }
        @Override public void close() throws Exception {
            try {
                input.close();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroy();
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IllegalStateException("Local drill node did not terminate");
                    }
                }
            } finally { reader.shutdownNow(); output.close(); }
        }
    }
}
