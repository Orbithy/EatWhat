package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.service.storage.FileAliasResolver;
import you.v50to.eatwhat.service.storage.FileLifecycleService;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for file reference release/restore mechanism with live view and tombstone tracking.
 * Tests the V29 release event log, live view filtering, and tombstone state transitions.
 */
class FileReferenceReleaseIT {
    private static GenericContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static DataSource dataSource;
    private static FileLifecycleService lifecycle;
    private static FileAliasResolver aliasResolver;

    @BeforeAll
    static void start() {
        postgres = IsolatedContainers.postgres("file-release");
        postgres.start();
        String database = "it_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE),
                IsolatedContainers.USER, IsolatedContainers.PASSWORD)).execute("CREATE DATABASE " + database + " TEMPLATE template0");
        String url = IsolatedContainers.jdbcUrl(postgres, database);
        Flyway.configure().dataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true).load().migrate();
        DataSource ds = new DriverManagerDataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD);
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
        PlatformTransactionManager txManager = new DataSourceTransactionManager(ds);
        lifecycle = new FileLifecycleService(jdbc, null, null, null, txManager);
        aliasResolver = new FileAliasResolver(jdbc);
    }

    @AfterAll
    static void stop() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void fileReferenceEventTableEnforcesAppendOnly() {
        UUID fileId = UUID.randomUUID();
        String logicalKey = "test/append-only-" + UUID.randomUUID();

        // Setup: create a reference
        setupFileReference(fileId, logicalKey, "PRIVATE");

        long eventId = jdbc.queryForObject(
            "INSERT INTO eatwhat_file.file_reference_event(logical_key, ref_kind, ref_id, action, reason) " +
            "VALUES(?, 'refkind', 'refid', 'RELEASE', 'ADMIN') RETURNING event_id",
            Long.class, logicalKey);
        assertTrue(eventId > 0, "Event inserted successfully");

        // Attempt UPDATE should fail
        assertThrows(DataIntegrityViolationException.class, () ->
            jdbc.update("UPDATE eatwhat_file.file_reference_event SET action='RESTORE' WHERE event_id=?", eventId));

        // Attempt DELETE should fail
        assertThrows(DataIntegrityViolationException.class, () ->
            jdbc.update("DELETE FROM eatwhat_file.file_reference_event WHERE event_id=?", eventId));
    }

    @Test
    void fileReferenceLiveViewExcludesReleasedAndIncludesRestored() {
        UUID fileId = UUID.randomUUID();
        String logicalKey = "test/live-view-" + UUID.randomUUID();

        // Setup: create a reference
        setupFileReference(fileId, logicalKey, "PRIVATE");

        // Reference is live initially
        Integer liveCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM eatwhat_file.file_reference_live WHERE logical_key=?",
            Integer.class, logicalKey);
        assertEquals(1, liveCount, "Reference is live before release");

        // Release it
        jdbc.update("INSERT INTO eatwhat_file.file_reference_event(logical_key, ref_kind, ref_id, action, reason) " +
                "VALUES(?, 'refkind', 'refid', 'RELEASE', 'ADMIN')", logicalKey);

        // Reference is not live after release
        liveCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM eatwhat_file.file_reference_live WHERE logical_key=?",
            Integer.class, logicalKey);
        assertEquals(0, liveCount, "Reference excluded from live view after RELEASE");

        // Restore it
        jdbc.update("INSERT INTO eatwhat_file.file_reference_event(logical_key, ref_kind, ref_id, action, reason) " +
                "VALUES(?, 'refkind', 'refid', 'RESTORE', 'ADMIN')", logicalKey);

        // Reference is live again after restore
        liveCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM eatwhat_file.file_reference_live WHERE logical_key=?",
            Integer.class, logicalKey);
        assertEquals(1, liveCount, "Reference re-included in live view after RESTORE");
    }

    @Test
    void tombstoneTransitionsOnReleaseAndRestore() {
        UUID fileId = UUID.randomUUID();
        String logicalKey = "test/tombstone-" + UUID.randomUUID();

        // Setup: create a PUBLIC reference
        setupFileReference(fileId, logicalKey, "PUBLIC");

        // Release it
        jdbc.update("INSERT INTO eatwhat_file.file_reference_event(logical_key, ref_kind, ref_id, action, reason) " +
                "VALUES(?, 'refkind', 'refid', 'RELEASE', 'ADMIN')", logicalKey);

        // Tombstone should be created with unreferenced_since set and had_public_ref=true
        var tombstone = jdbc.queryForMap(
            "SELECT unreferenced_since, had_public_ref, revived_at FROM eatwhat_file.file_tombstone WHERE file_id=?",
            fileId);
        assertNotNull(tombstone.get("unreferenced_since"), "unreferenced_since must be set");
        assertTrue((Boolean) tombstone.get("had_public_ref"), "had_public_ref must be true for PUBLIC reference");
        assertNull(tombstone.get("revived_at"), "revived_at must be NULL initially");

        Object unreferencedSince1 = tombstone.get("unreferenced_since");

        // Restore it (re-reference)
        jdbc.update("INSERT INTO eatwhat_file.file_reference_event(logical_key, ref_kind, ref_id, action, reason) " +
                "VALUES(?, 'refkind', 'refid', 'RESTORE', 'ADMIN')", logicalKey);

        // Tombstone should have unreferenced_since cleared and revived_at set
        tombstone = jdbc.queryForMap(
            "SELECT unreferenced_since, revived_at FROM eatwhat_file.file_tombstone WHERE file_id=?",
            fileId);
        assertNull(tombstone.get("unreferenced_since"), "unreferenced_since must be cleared after RESTORE");
        assertNotNull(tombstone.get("revived_at"), "revived_at must be set after RESTORE");
    }

    @Test
    void releaseReferencesMatchesEntityIdWithWildcard() {
        UUID fileId = UUID.randomUUID();
        String logicalKey1 = "test/wildcard-" + UUID.randomUUID();
        String logicalKey2 = "test/wildcard-" + UUID.randomUUID();
        String logicalKey3 = "test/wildcard-" + UUID.randomUUID();

        // Setup: create references with different ref_ids
        setupFileReference(fileId, logicalKey1, "PRIVATE");
        setupFileReference(UUID.randomUUID(), logicalKey2, "PRIVATE");
        setupFileReference(UUID.randomUUID(), logicalKey3, "PRIVATE");

        jdbc.update("INSERT INTO eatwhat_file.file_reference(logical_key, ref_kind, ref_id) VALUES(?, 'test', '12')", logicalKey1);
        jdbc.update("INSERT INTO eatwhat_file.file_reference(logical_key, ref_kind, ref_id) VALUES(?, 'test', '12:0')", logicalKey1);
        jdbc.update("INSERT INTO eatwhat_file.file_reference(logical_key, ref_kind, ref_id) VALUES(?, 'test', '12:1')", logicalKey1);
        jdbc.update("INSERT INTO eatwhat_file.file_reference(logical_key, ref_kind, ref_id) VALUES(?, 'test', '123')", logicalKey2);
        jdbc.update("INSERT INTO eatwhat_file.file_reference(logical_key, ref_kind, ref_id) VALUES(?, 'test', '12x')", logicalKey3);

        // Release '12' which should match '12' and '12:0', '12:1' but NOT '123' or '12x'
        int released = lifecycle.releaseReferences("test", "12", null, "ADMIN");
        assertEquals(3, released, "Should release 3 references: '12', '12:0', '12:1'");

        // Verify correct refs were released
        Integer live12 = jdbc.queryForObject(
            "SELECT COUNT(*) FROM eatwhat_file.file_reference_live WHERE logical_key=? AND ref_id='12'",
            Integer.class, logicalKey1);
        assertEquals(0, live12, "'12' should be released");

        Integer live120 = jdbc.queryForObject(
            "SELECT COUNT(*) FROM eatwhat_file.file_reference_live WHERE logical_key=? AND ref_id='12:0'",
            Integer.class, logicalKey1);
        assertEquals(0, live120, "'12:0' should be released");

        Integer live123 = jdbc.queryForObject(
            "SELECT COUNT(*) FROM eatwhat_file.file_reference_live WHERE logical_key=? AND ref_id='123'",
            Integer.class, logicalKey2);
        assertEquals(1, live123, "'123' should NOT be released");

        Integer live12x = jdbc.queryForObject(
            "SELECT COUNT(*) FROM eatwhat_file.file_reference_live WHERE logical_key=? AND ref_id='12x'",
            Integer.class, logicalKey3);
        assertEquals(1, live12x, "'12x' should NOT be released");
    }

    @Test
    void releaseReferencesValidatesReason() {
        UUID fileId = UUID.randomUUID();
        String logicalKey = "test/reason-" + UUID.randomUUID();
        setupFileReference(fileId, logicalKey, "PRIVATE");
        jdbc.update("INSERT INTO eatwhat_file.file_reference(logical_key, ref_kind, ref_id) VALUES(?, 'test', 'id')", logicalKey);

        assertThrows(IllegalArgumentException.class, () ->
            lifecycle.releaseReferences("test", "id", null, "INVALID_REASON"));
    }

    @Test
    void releaseReferencesReturnsZeroForEmptyKeys() {
        UUID fileId = UUID.randomUUID();
        String logicalKey = "test/empty-" + UUID.randomUUID();
        setupFileReference(fileId, logicalKey, "PRIVATE");
        jdbc.update("INSERT INTO eatwhat_file.file_reference(logical_key, ref_kind, ref_id) VALUES(?, 'test', 'id')", logicalKey);

        int released = lifecycle.releaseReferences("test", "id", List.of(), "ADMIN");
        assertEquals(0, released, "Empty keys collection should release nothing");
    }

    @Test
    void aliasResolverReturnsNullForReleasedPublicKey() {
        UUID fileId = UUID.randomUUID();
        String logicalKey = "test/resolver-" + UUID.randomUUID();
        String finalKey = "verified/" + UUID.randomUUID().toString().replace("-", "");

        // Setup: create a VERIFIED PUBLIC reference with LOCAL replica
        setupFileReference(fileId, logicalKey, "PUBLIC", finalKey);

        // Before release, resolver should return the key
        FileAliasResolver.Resolution before = aliasResolver.resolve(logicalKey);
        assertTrue(before.managed(), "Reference should be managed");
        assertEquals(finalKey, before.readableObjectKey(), "Should return final key for PUBLIC reference");

        // Release the reference
        jdbc.update("INSERT INTO eatwhat_file.file_reference_event(logical_key, ref_kind, ref_id, action, reason) " +
                "VALUES(?, 'refkind', 'refid', 'RELEASE', 'ADMIN')", logicalKey);

        // After release, resolver should return null (no readable key)
        FileAliasResolver.Resolution after = aliasResolver.resolve(logicalKey);
        assertTrue(after.managed(), "Reference is still managed");
        assertNull(after.readableObjectKey(), "Should return null for released PUBLIC reference");

        // Restore the reference
        jdbc.update("INSERT INTO eatwhat_file.file_reference_event(logical_key, ref_kind, ref_id, action, reason) " +
                "VALUES(?, 'refkind', 'refid', 'RESTORE', 'ADMIN')", logicalKey);

        // After restore, resolver should return the key again
        FileAliasResolver.Resolution restored = aliasResolver.resolve(logicalKey);
        assertTrue(restored.managed(), "Reference should be managed");
        assertEquals(finalKey, restored.readableObjectKey(), "Should return final key after restore");
    }

    @Test
    void releaseInsideRolledBackTransactionLeavesReferenceAlive() {
        UUID fileId = UUID.randomUUID();
        String logicalKey = "test/rollback-" + UUID.randomUUID();
        setupFileReference(fileId, logicalKey, "PRIVATE");
        jdbc.update("INSERT INTO eatwhat_file.file_reference(logical_key, ref_kind, ref_id) VALUES(?, 'test', 'rb')", logicalKey);

        String liveSql = "SELECT COUNT(*) FROM eatwhat_file.file_reference_live WHERE logical_key=? AND ref_kind='test' AND ref_id='rb'";
        assertEquals(1, jdbc.queryForObject(liveSql, Integer.class, logicalKey), "Reference is live before the release");

        // lifecycle and jdbc share dataSource, so this transaction really spans the release.
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.executeWithoutResult(status -> {
            assertEquals(1, lifecycle.releaseReferences("test", "rb", null, "ENTITY_DELETED"));
            assertEquals(0, jdbc.queryForObject(liveSql, Integer.class, logicalKey), "Released inside the transaction");
            status.setRollbackOnly();
        });

        assertEquals(1, jdbc.queryForObject(liveSql, Integer.class, logicalKey),
                "A rolled-back business transaction rolls back the release");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM eatwhat_file.file_reference_event WHERE logical_key=? AND ref_id='rb'", Integer.class, logicalKey));
    }

    private void setupFileReference(UUID fileId, String logicalKey, String visibility) {
        String finalKey = "verified/" + UUID.randomUUID().toString().replace("-", "");
        setupFileReference(fileId, logicalKey, visibility, finalKey);
    }

    private void setupFileReference(UUID fileId, String logicalKey, String visibility, String finalKey) {
        UUID ownerGid = UUID.randomUUID();

        // Create file_object
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key) VALUES(?, ?, 'test', ?)",
                fileId, ownerGid, logicalKey);

        // Create file_revision
        String sha256 = "a".repeat(64); // 64 hex chars
        jdbc.update("INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at) " +
                "VALUES(?, 1, ?, ?, 1024, 'text/plain', 'VERIFIED', clock_timestamp())",
                fileId, finalKey, sha256);

        // Create file_replica
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state) " +
                "VALUES(?, 1, 'LOCAL', ?, 'AVAILABLE_LOCAL')",
                fileId, finalKey);

        // Create file_key_alias
        jdbc.update("INSERT INTO eatwhat_file.file_key_alias(logical_key, file_id, revision, owner_gid, biz) " +
                "VALUES(?, ?, 1, ?, 'test')",
                logicalKey, fileId, ownerGid);

        // Create file_reference
        jdbc.update("INSERT INTO eatwhat_file.file_reference(logical_key, ref_kind, ref_id, visibility) VALUES(?, 'refkind', 'refid', ?)",
                logicalKey, visibility);
    }
}
