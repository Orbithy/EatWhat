package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.service.storage.replication.FilePublicationService;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for FilePublicationService state transitions.
 * Tests the publication state machine:
 * - AVAILABLE_GLOBAL: all conditions met
 * - PAUSED_CONSENT: any replica paused
 * - PEER_PENDING: peer replica exists but not verified
 * - LOCAL_ONLY: default/fallback
 */
class FilePublicationServiceIT {
    private static GenericContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static FilePublicationService publicationService;

    @BeforeAll
    static void start() {
        postgres = IsolatedContainers.postgres("publication-svc");
        postgres.start();
        String database = "it_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE),
                IsolatedContainers.USER, IsolatedContainers.PASSWORD)).execute("CREATE DATABASE " + database + " TEMPLATE template0");
        String url = IsolatedContainers.jdbcUrl(postgres, database);
        Flyway.configure().dataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true).load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD));
        publicationService = new FilePublicationService(jdbc);
    }

    @AfterAll
    static void stop() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void recomputeStartsWithLocalOnly() {
        // Setup: create a VERIFIED revision with LOCAL AVAILABLE_LOCAL replica
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key) VALUES(?, ?, 'test', 'test/' || ?)",
                fileId, ownerGid, fileId.toString());
        jdbc.update("INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at) " +
                "VALUES(?, 1, 'verified/' || ?::text, 'abc123', 100, 'text/plain', 'VERIFIED', clock_timestamp())",
                fileId, fileId.toString());
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at) " +
                "VALUES(?, 1, 'LOCAL', 'verified/abc', 'AVAILABLE_LOCAL', 'abc123', 100, clock_timestamp())",
                fileId);

        // Act: recompute publication
        publicationService.recompute(fileId, 1);

        // Assert: publication is LOCAL_ONLY
        String state = jdbc.queryForObject("SELECT global_state FROM eatwhat_file.file_publication WHERE file_id=? AND revision=1",
                String.class, fileId);
        assertEquals("LOCAL_ONLY", state);
    }

    @Test
    void recomputePeerPendingWhenPeerReplicaExists() {
        // Setup: revision with LOCAL AVAILABLE_LOCAL and PEER PENDING_SYNC
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key) VALUES(?, ?, 'test', 'test/' || ?)",
                fileId, ownerGid, fileId.toString());
        jdbc.update("INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at) " +
                "VALUES(?, 1, 'verified/' || ?::text, 'abc123', 100, 'text/plain', 'VERIFIED', clock_timestamp())",
                fileId, fileId.toString());
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at) " +
                "VALUES(?, 1, 'LOCAL', 'verified/abc', 'AVAILABLE_LOCAL', 'abc123', 100, clock_timestamp())",
                fileId);
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state) " +
                "VALUES(?, 1, 'PEER', 'verified/abc', 'PENDING_SYNC')",
                fileId);

        // Act
        publicationService.recompute(fileId, 1);

        // Assert: publication is PEER_PENDING
        String state = jdbc.queryForObject("SELECT global_state FROM eatwhat_file.file_publication WHERE file_id=? AND revision=1",
                String.class, fileId);
        assertEquals("PEER_PENDING", state);
    }

    @Test
    void recomputeStaysPeerPendingWhenPeerVerifiedButNotConfirmed() {
        // Setup: peer is PEER_VERIFIED but peer_confirmed_at is null
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key) VALUES(?, ?, 'test', 'test/' || ?)",
                fileId, ownerGid, fileId.toString());
        jdbc.update("INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at) " +
                "VALUES(?, 1, 'verified/' || ?::text, 'abc123', 100, 'text/plain', 'VERIFIED', clock_timestamp())",
                fileId, fileId.toString());
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at) " +
                "VALUES(?, 1, 'LOCAL', 'verified/abc', 'AVAILABLE_LOCAL', 'abc123', 100, clock_timestamp())",
                fileId);
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at) " +
                "VALUES(?, 1, 'PEER', 'verified/abc', 'PEER_VERIFIED', 'abc123', 100, clock_timestamp())",
                fileId);
        jdbc.update("INSERT INTO eatwhat_file.file_publication(file_id, revision, global_state, created_at, updated_at) " +
                "VALUES(?, 1, 'PEER_PENDING', clock_timestamp(), clock_timestamp())",
                fileId);

        // Act: recompute without marking peer_confirmed_at
        publicationService.recompute(fileId, 1);

        // Assert: still PEER_PENDING (not AVAILABLE_GLOBAL)
        String state = jdbc.queryForObject("SELECT global_state FROM eatwhat_file.file_publication WHERE file_id=? AND revision=1",
                String.class, fileId);
        assertEquals("PEER_PENDING", state);
    }

    @Test
    void recomputeAvailableGlobalWhenAllConditionsMet() {
        // Setup: all conditions for AVAILABLE_GLOBAL
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key) VALUES(?, ?, 'test', 'test/' || ?)",
                fileId, ownerGid, fileId.toString());
        jdbc.update("INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at) " +
                "VALUES(?, 1, 'verified/' || ?::text, 'abc123', 100, 'text/plain', 'VERIFIED', clock_timestamp())",
                fileId, fileId.toString());
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at) " +
                "VALUES(?, 1, 'LOCAL', 'verified/abc', 'AVAILABLE_LOCAL', 'abc123', 100, clock_timestamp())",
                fileId);
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at) " +
                "VALUES(?, 1, 'PEER', 'verified/abc', 'PEER_VERIFIED', 'abc123', 100, clock_timestamp())",
                fileId);
        jdbc.update("INSERT INTO eatwhat_file.file_publication(file_id, revision, global_state, peer_confirmed_at, created_at, updated_at) " +
                "VALUES(?, 1, 'PEER_PENDING', clock_timestamp(), clock_timestamp(), clock_timestamp())",
                fileId);

        // Act: recompute (peer_confirmed_at is already set)
        publicationService.recompute(fileId, 1);

        // Assert: AVAILABLE_GLOBAL
        String state = jdbc.queryForObject("SELECT global_state FROM eatwhat_file.file_publication WHERE file_id=? AND revision=1",
                String.class, fileId);
        assertEquals("AVAILABLE_GLOBAL", state);
    }

    @Test
    void recomputePausedWhenAnyReplicaPaused() {
        // Setup: peer is PAUSED_CONSENT
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key) VALUES(?, ?, 'test', 'test/' || ?)",
                fileId, ownerGid, fileId.toString());
        jdbc.update("INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at) " +
                "VALUES(?, 1, 'verified/' || ?::text, 'abc123', 100, 'text/plain', 'VERIFIED', clock_timestamp())",
                fileId, fileId.toString());
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at) " +
                "VALUES(?, 1, 'LOCAL', 'verified/abc', 'AVAILABLE_LOCAL', 'abc123', 100, clock_timestamp())",
                fileId);
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state) " +
                "VALUES(?, 1, 'PEER', 'verified/abc', 'PAUSED_CONSENT')",
                fileId);

        // Act
        publicationService.recompute(fileId, 1);

        // Assert: PAUSED_CONSENT
        String state = jdbc.queryForObject("SELECT global_state FROM eatwhat_file.file_publication WHERE file_id=? AND revision=1",
                String.class, fileId);
        assertEquals("PAUSED", state);
    }

    @Test
    void markPeerConfirmedSetsTimestampAndRecomputes() {
        // Setup
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();
        jdbc.update("INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key) VALUES(?, ?, 'test', 'test/' || ?)",
                fileId, ownerGid, fileId.toString());
        jdbc.update("INSERT INTO eatwhat_file.file_revision(file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at) " +
                "VALUES(?, 1, 'verified/' || ?::text, 'abc123', 100, 'text/plain', 'VERIFIED', clock_timestamp())",
                fileId, fileId.toString());
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at) " +
                "VALUES(?, 1, 'LOCAL', 'verified/abc', 'AVAILABLE_LOCAL', 'abc123', 100, clock_timestamp())",
                fileId);
        jdbc.update("INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at) " +
                "VALUES(?, 1, 'PEER', 'verified/abc', 'PEER_VERIFIED', 'abc123', 100, clock_timestamp())",
                fileId);
        jdbc.update("INSERT INTO eatwhat_file.file_publication(file_id, revision, global_state, created_at, updated_at) " +
                "VALUES(?, 1, 'PEER_PENDING', clock_timestamp(), clock_timestamp())",
                fileId);

        // Act: mark peer confirmed
        publicationService.markPeerConfirmed(fileId, 1);

        // Assert: peer_confirmed_at is set and state is AVAILABLE_GLOBAL
        var row = jdbc.queryForObject("SELECT peer_confirmed_at, global_state FROM eatwhat_file.file_publication WHERE file_id=? AND revision=1",
                (rs, rowNum) -> new Object[]{rs.getTimestamp(1), rs.getString(2)},
                fileId);
        assertNotNull(row[0], "peer_confirmed_at should be set");
        assertEquals("AVAILABLE_GLOBAL", row[1]);
    }
}
