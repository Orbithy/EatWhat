package you.v50to.eatwhat.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.service.storage.replication.FileReplicaCatalog;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for FileReplicaCatalog.recordPeerRevision.
 * Tests idempotency, conflict detection, and job enqueueing.
 */
class FileReplicaCatalogIT {
    private static GenericContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static FileReplicaCatalog catalog;

    @BeforeAll
    static void start() {
        postgres = IsolatedContainers.postgres("catalog-svc");
        postgres.start();
        String database = "it_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(IsolatedContainers.jdbcUrl(postgres, IsolatedContainers.DATABASE),
                IsolatedContainers.USER, IsolatedContainers.PASSWORD)).execute("CREATE DATABASE " + database + " TEMPLATE template0");
        String url = IsolatedContainers.jdbcUrl(postgres, database);
        Flyway.configure().dataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD)
                .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true).load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD));
        catalog = new FileReplicaCatalog(jdbc);
    }

    @AfterAll
    static void stop() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void recordPeerRevisionCreatesAllRows() {
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();

        FileReplicaCatalog.PeerRevision peer = new FileReplicaCatalog.PeerRevision(
                fileId, 1, ownerGid, "test", "test/" + fileId, "verified/" + fileId,
                "a".repeat(64), 1024, "application/octet-stream");

        FileReplicaCatalog.Result result = catalog.recordPeerRevision(peer);

        assertEquals(FileReplicaCatalog.Result.RECORDED, result);

        // Verify file_object was created
        Integer objCount = jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_object WHERE file_id=?", Integer.class, fileId);
        assertEquals(1, objCount);

        // Verify file_revision was created
        Integer revCount = jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_revision WHERE file_id=? AND revision=1", Integer.class, fileId);
        assertEquals(1, revCount);

        // Verify LOCAL replica was created (PENDING_SYNC)
        Integer localCount = jdbc.queryForObject(
                "SELECT count(*) FROM eatwhat_file.file_replica WHERE file_id=? AND revision=1 AND site='LOCAL' AND state='PENDING_SYNC'",
                Integer.class, fileId);
        assertEquals(1, localCount);

        // Verify PEER replica was created
        Integer peerCount = jdbc.queryForObject(
                "SELECT count(*) FROM eatwhat_file.file_replica WHERE file_id=? AND revision=1 AND site='PEER' AND state='PENDING_SYNC'",
                Integer.class, fileId);
        assertEquals(1, peerCount);

        // Verify publication was created
        Integer pubCount = jdbc.queryForObject(
                "SELECT count(*) FROM eatwhat_file.file_publication WHERE file_id=? AND revision=1",
                Integer.class, fileId);
        assertEquals(1, pubCount);

        // Verify PULL_FROM_PEER job was created
        Integer jobCount = jdbc.queryForObject(
                "SELECT count(*) FROM eatwhat_file.file_transfer_job WHERE file_id=? AND revision=1 AND kind='PULL_FROM_PEER' AND target_site='LOCAL'",
                Integer.class, fileId);
        assertEquals(1, jobCount);
    }

    @Test
    void recordPeerRevisionIsIdempotentOnSameShaSize() {
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();

        FileReplicaCatalog.PeerRevision peer = new FileReplicaCatalog.PeerRevision(
                fileId, 1, ownerGid, "test", "test/" + fileId, "verified/" + fileId,
                "a".repeat(64), 1024, "application/octet-stream");

        FileReplicaCatalog.Result result1 = catalog.recordPeerRevision(peer);
        assertEquals(FileReplicaCatalog.Result.RECORDED, result1);

        // Replay with identical metadata
        FileReplicaCatalog.Result result2 = catalog.recordPeerRevision(peer);
        assertEquals(FileReplicaCatalog.Result.ALREADY_RECORDED, result2);

        // Verify only one revision exists
        Integer revCount = jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.file_revision WHERE file_id=? AND revision=1", Integer.class, fileId);
        assertEquals(1, revCount);
    }

    @Test
    void recordPeerRevisionConflictOnDifferentShaSize() {
        UUID fileId = UUID.randomUUID();
        UUID ownerGid = UUID.randomUUID();

        // First recording
        FileReplicaCatalog.PeerRevision peer1 = new FileReplicaCatalog.PeerRevision(
                fileId, 1, ownerGid, "test", "test/" + fileId, "verified/" + fileId,
                "a".repeat(64), 1024, "application/octet-stream");
        FileReplicaCatalog.Result result1 = catalog.recordPeerRevision(peer1);
        assertEquals(FileReplicaCatalog.Result.RECORDED, result1);

        // Different sha256 but same fileId/revision
        FileReplicaCatalog.PeerRevision peer2 = new FileReplicaCatalog.PeerRevision(
                fileId, 1, ownerGid, "test", "test/" + fileId, "verified/" + fileId,
                "different_sha256", 2048, "application/octet-stream");
        FileReplicaCatalog.Result result2 = catalog.recordPeerRevision(peer2);
        assertEquals(FileReplicaCatalog.Result.CONFLICT, result2);

        // Verify alert was created
        Integer alertCount = jdbc.queryForObject(
                "SELECT count(*) FROM eatwhat_file.file_replica_alert WHERE file_id=? AND revision=1 AND alert_type='REVISION_MISMATCH'",
                Integer.class, fileId);
        assertEquals(1, alertCount);

        // Verify original revision is unchanged
        String sha = jdbc.queryForObject(
                "SELECT sha256 FROM eatwhat_file.file_revision WHERE file_id=? AND revision=1",
                String.class, fileId);
        assertEquals("a".repeat(64), sha);
    }
}
