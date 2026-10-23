package you.v50to.eatwhat.service.storage.replication;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import you.v50to.eatwhat.account.ConsentStore;

import java.util.Objects;
import java.util.UUID;

/**
 * Enqueues file replication jobs when a revision becomes VERIFIED (canonical).
 * Works in the CALLER's transaction (must have active transaction context).
 * Creates:
 * - file_replica PEER row (state PENDING_SYNC)
 * - file_publication row (state PEER_PENDING)
 * - file_transfer_job for COPY_TO_PEER (target_site='PEER')
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.storage.files.replication.enabled", havingValue = "true")
public class FileReplicationEnqueuer {

    private final JdbcTemplate jdbc;
    private final org.springframework.beans.factory.ObjectProvider<ConsentStore> consentStores;
    private final you.v50to.eatwhat.cluster.ClusterMembershipView membership;

    public FileReplicationEnqueuer(JdbcTemplate jdbc, org.springframework.beans.factory.ObjectProvider<ConsentStore> consentStores) {
        this.jdbc = Objects.requireNonNull(jdbc, "JdbcTemplate");
        this.consentStores = Objects.requireNonNull(consentStores, "ConsentStore provider");
        this.membership=new you.v50to.eatwhat.cluster.ClusterMembershipView(jdbc);
    }

    /**
     * Hook called right after a revision becomes VERIFIED (canonicalized).
     * Must be called in the SAME TRANSACTION as the canonicalize step.
     * Atomically inserts PEER replica, publication row, and transfer job.
     * All operations use ON CONFLICT DO NOTHING for idempotency.
     *
     * Propagation behavior:
     * - MANDATORY or SUPPORTS: requires an active transaction from caller (FileLifecycleService.canonicalize)
     * - If called outside a transaction, this will fail (by design: replication is part of canonicalization)
     *
     * @param fileId the file UUID (must exist in file_object)
     * @param revision the revision number (must be VERIFIED in file_revision)
     * @param finalKey the immutable object key (e.g., "verified/<uuid>")
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    private ConsentStore consentStoreOrNull() {
        ConsentStore store = consentStores.getIfAvailable();
        if (store == null) throw new IllegalStateException("No ConsentStore available");
        return store;
    }

    public void onRevisionVerified(UUID fileId, int revision, String finalKey) {
        Objects.requireNonNull(fileId, "fileId");
        Objects.requireNonNull(finalKey, "finalKey");
        if(membership.bound()){
            jdbc.update("INSERT INTO eatwhat_file.file_publication(file_id,revision,global_state) VALUES(?,?,'PEER_PENDING') ON CONFLICT DO NOTHING",fileId,revision);return;
        }

        // Query owner_gid from file_object to determine consent epoch
        UUID ownerGid = jdbc.queryForObject(
                "SELECT owner_gid FROM eatwhat_file.file_object WHERE file_id=?",
                UUID.class, fileId);

        // Try to resolve consent epoch from owner's current state
        // If ConsentStore lookup fails or user not found, consent_epoch stays NULL
        Long consentEpoch = null;
        try {
            // Convert ownerGid back to userId via entity_identity table
            Long userId = jdbc.queryForObject(
                    "SELECT numeric_id FROM eatwhat_identity.entity_identity WHERE global_id=? AND entity_type='users'",
                    Long.class, ownerGid);
            if (userId != null) {
                you.v50to.eatwhat.account.ConsentModels.State state = consentStoreOrNull().get(userId);
                if (state != null && state.status().equals("GRANTED")) {
                    consentEpoch = state.epoch();
                }
            }
        } catch (Exception e) {
            log.debug("Could not determine consent epoch for file {}: {}", fileId, e.getMessage());
            // Continue with NULL consent_epoch
        }

        // Insert PEER replica (idempotent)
        jdbc.update("""
                INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, updated_at)
                VALUES(?, ?, 'PEER', ?, 'PENDING_SYNC', clock_timestamp())
                ON CONFLICT(file_id, revision, site) DO NOTHING
                """, fileId, revision, finalKey);

        // Insert publication row (idempotent)
        jdbc.update("""
                INSERT INTO eatwhat_file.file_publication(file_id, revision, global_state, created_at, updated_at)
                VALUES(?, ?, 'PEER_PENDING', clock_timestamp(), clock_timestamp())
                ON CONFLICT(file_id, revision) DO NOTHING
                """, fileId, revision);

        // Insert transfer job (idempotent)
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO eatwhat_file.file_transfer_job
                  (job_id, file_id, revision, kind, target_site, status, attempts, next_attempt_at, consent_epoch, created_at, updated_at)
                VALUES(?, ?, ?, 'COPY_TO_PEER', 'PEER', 'READY', 0, clock_timestamp(), ?, clock_timestamp(), clock_timestamp())
                ON CONFLICT(file_id, revision, target_site) DO NOTHING
                """, jobId, fileId, revision, consentEpoch);

        log.debug("Enqueued COPY_TO_PEER job for file {} revision {}", fileId, revision);
    }

    /**
     * Find VERIFIED revisions (canonical) that lack a PEER replica and enqueue them.
     * Finds up to `limit` revisions; each one is enqueued in its own transaction (joining the caller's, if any).
     * Revisions whose LOCAL replica was physically deleted by the GC are skipped: there are no bytes to replicate.
     * Returns the count of revisions successfully queued.
     *
     * <p>The per-revision transaction takes a shared row lock on the revision's tombstone (when one exists), which
     * conflicts with the GC's exclusive lock, and then re-checks that the LOCAL replica is not DELETED. The runtime
     * role cannot lock file_revision rows (no UPDATE privilege), so the tombstone is the shared guard.
     *
     * @param limit maximum number of revisions to process in this call
     * @return count of revisions where jobs were created
     */
    public int enqueueMissing(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Limit must be 1-100");
        }

        // Find VERIFIED revisions lacking a PEER replica (and not already physically deleted)
        var unreplicated = jdbc.query("""
                SELECT fr.file_id, fr.revision, fr.final_key, fo.owner_gid
                FROM eatwhat_file.file_revision fr
                JOIN eatwhat_file.file_object fo ON fo.file_id=fr.file_id
                WHERE fr.state='VERIFIED'
                  AND NOT EXISTS(SELECT 1 FROM eatwhat_file.file_replica
                                WHERE file_id=fr.file_id AND revision=fr.revision AND site='PEER')
                  AND NOT EXISTS(SELECT 1 FROM eatwhat_file.file_replica
                                WHERE file_id=fr.file_id AND revision=fr.revision AND site='LOCAL' AND state IN ('DELETING','DELETED'))
                ORDER BY fr.created_at
                LIMIT ?
                """,
                (rs, rowNum) -> new ReplicationTask(
                        rs.getObject(1, UUID.class),
                        rs.getInt(2),
                        rs.getString(3),
                        rs.getObject(4, UUID.class)),
                limit);

        int enqueued = 0;
        for (ReplicationTask task : unreplicated) {
            try {
                Boolean queued = inTransaction(() -> enqueueTask(task));
                if (Boolean.TRUE.equals(queued)) {
                    enqueued++;
                    log.debug("Enqueued missing replication for file {} revision {}", task.fileId, task.revision);
                }
            } catch (Exception e) {
                log.warn("Failed to enqueue replication for file {} revision {}: {}",
                        task.fileId, task.revision, e.getMessage());
            }
        }

        return enqueued;
    }

    private Boolean inTransaction(java.util.function.Supplier<Boolean> body) {
        javax.sql.DataSource dataSource = jdbc.getDataSource();
        if (dataSource == null) {
            return body.get();
        }
        var template = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        return template.execute(status -> body.get());
    }

    /** Runs inside a transaction: shared tombstone lock, DELETED re-check, then replica, publication and job. */
    private boolean enqueueTask(ReplicationTask task) {
        // Wait for (or conflict with) a GC that is deleting this revision; a no-op when no tombstone exists.
        jdbc.query("SELECT 1 FROM eatwhat_file.file_tombstone WHERE file_id=? AND revision=? FOR SHARE",
                rs -> { }, task.fileId, task.revision);
        boolean deleted = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_file.file_replica
                              WHERE file_id=? AND revision=? AND site='LOCAL' AND state IN ('DELETING','DELETED'))
                """, Boolean.class, task.fileId, task.revision));
        if (deleted) {
            log.debug("Skipping replication of file {} revision {}: local object was physically deleted", task.fileId, task.revision);
            return false;
        }

        Long consentEpoch = null;
        try {
            Long userId = jdbc.queryForObject(
                    "SELECT numeric_id FROM eatwhat_identity.entity_identity WHERE global_id=? AND entity_type='users'",
                    Long.class, task.ownerGid);
            if (userId != null) {
                you.v50to.eatwhat.account.ConsentModels.State state = consentStoreOrNull().get(userId);
                if (state != null && state.status().equals("GRANTED")) {
                    consentEpoch = state.epoch();
                }
            }
        } catch (Exception e) {
            log.debug("Could not determine consent epoch for file {}: {}", task.fileId, e.getMessage());
        }

        // Insert replica, publication, and job
        jdbc.update("""
                INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, updated_at)
                VALUES(?, ?, 'PEER', ?, 'PENDING_SYNC', clock_timestamp())
                ON CONFLICT DO NOTHING
                """, task.fileId, task.revision, task.finalKey);

        jdbc.update("""
                INSERT INTO eatwhat_file.file_publication(file_id, revision, global_state, created_at, updated_at)
                VALUES(?, ?, 'PEER_PENDING', clock_timestamp(), clock_timestamp())
                ON CONFLICT DO NOTHING
                """, task.fileId, task.revision);

        UUID jobId = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO eatwhat_file.file_transfer_job
                  (job_id, file_id, revision, kind, target_site, status, attempts, next_attempt_at, consent_epoch, created_at, updated_at)
                VALUES(?, ?, ?, 'COPY_TO_PEER', 'PEER', 'READY', 0, clock_timestamp(), ?, clock_timestamp(), clock_timestamp())
                ON CONFLICT DO NOTHING
                """, jobId, task.fileId, task.revision, consentEpoch);
        return inserted > 0;
    }

    private record ReplicationTask(UUID fileId, int revision, String finalKey, UUID ownerGid) { }
}
