package you.v50to.eatwhat.service.storage.replication;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/**
 * Manages file publication state transitions based on replica availability.
 * Derives file_publication.global_state from replica state machine:
 * - AVAILABLE_GLOBAL: revision VERIFIED + LOCAL AVAILABLE_LOCAL w/ verified_sha256 match
 *   + PEER PEER_VERIFIED w/ verified_sha256 match + peer_confirmed_at not null
 *   + no PAUSED_CONSENT replica
 * - PAUSED: any replica in PAUSED_CONSENT state
 * - PEER_PENDING: a PEER replica exists in any non-verified state
 * - LOCAL_ONLY: default / fallback
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.storage.files.replication.enabled", havingValue = "true")
public class FilePublicationService {

    private final JdbcTemplate jdbc;
    private final you.v50to.eatwhat.cluster.ClusterMembershipView membership;

    public FilePublicationService(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "JdbcTemplate");
        this.membership=new you.v50to.eatwhat.cluster.ClusterMembershipView(jdbc);
    }

    /**
     * Recompute and persist the global_state for a file revision publication.
     * Idempotent: updates existing publication row or inserts LOCAL_ONLY row if missing.
     * Uses one atomic SQL statement to ensure consistency.
     *
     * @param fileId file UUID
     * @param revision file revision number
     */
    public void recompute(UUID fileId, int revision) {
        Objects.requireNonNull(fileId, "fileId");
        if(membership.bound()){
            jdbc.update("""
                UPDATE eatwhat_file.file_publication p SET global_state=CASE
                  WHEN EXISTS(SELECT 1 FROM eatwhat_cluster.replica_manifest m WHERE m.file_id=p.file_id AND m.revision=p.revision
                    AND m.epoch=(SELECT epoch FROM eatwhat_cluster.local_state WHERE singleton)
                    AND jsonb_array_length(m.required_members)>1
                    AND EXISTS(SELECT 1 FROM eatwhat_file.file_replica local WHERE local.file_id=p.file_id AND local.revision=p.revision AND local.site='LOCAL' AND local.state='AVAILABLE_LOCAL')
                    AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(m.required_members) member
                      WHERE NOT EXISTS(SELECT 1 FROM eatwhat_cluster.replica_fact f JOIN eatwhat_file.file_revision r USING(file_id,revision)
                        WHERE f.file_id=p.file_id AND f.revision=p.revision AND f.site_id=member->>'siteId'
                          AND f.instance_id=(member->>'instanceId')::uuid AND f.state='VERIFIED'
                          AND f.sha256=r.sha256 AND f.size_bytes=r.size_bytes))) THEN 'AVAILABLE_GLOBAL'
                  WHEN EXISTS(SELECT 1 FROM eatwhat_cluster.replica_manifest m WHERE m.file_id=p.file_id AND m.revision=p.revision AND jsonb_array_length(m.required_members)<=1) THEN 'LOCAL_ONLY'
                  ELSE 'PEER_PENDING' END,updated_at=clock_timestamp()
                WHERE p.file_id=? AND p.revision=?
                """,fileId,revision);return;
        }
        // UPDATE first: INSERT ... ON CONFLICT would check the proposed row's CHECK constraints
        // (AVAILABLE_GLOBAL requires peer_confirmed_at) before looking at the existing row.
        int updated = jdbc.update("""
                UPDATE eatwhat_file.file_publication p SET
                  global_state=CASE
                    WHEN fr.state='VERIFIED'
                      AND p.peer_confirmed_at IS NOT NULL
                      AND EXISTS(SELECT 1 FROM eatwhat_file.file_replica r
                                 WHERE r.file_id=fr.file_id AND r.revision=fr.revision AND r.site='LOCAL'
                                   AND r.state='AVAILABLE_LOCAL' AND r.verified_sha256=fr.sha256)
                      AND EXISTS(SELECT 1 FROM eatwhat_file.file_replica r
                                 WHERE r.file_id=fr.file_id AND r.revision=fr.revision AND r.site='PEER'
                                   AND r.state='PEER_VERIFIED' AND r.verified_sha256=fr.sha256)
                      AND NOT EXISTS(SELECT 1 FROM eatwhat_file.file_replica r
                                     WHERE r.file_id=fr.file_id AND r.revision=fr.revision AND r.state='PAUSED_CONSENT')
                    THEN 'AVAILABLE_GLOBAL'
                    WHEN EXISTS(SELECT 1 FROM eatwhat_file.file_replica r
                                WHERE r.file_id=fr.file_id AND r.revision=fr.revision AND r.state='PAUSED_CONSENT')
                    THEN 'PAUSED'
                    WHEN EXISTS(SELECT 1 FROM eatwhat_file.file_replica r
                                WHERE r.file_id=fr.file_id AND r.revision=fr.revision AND r.site='PEER')
                    THEN 'PEER_PENDING'
                    ELSE 'LOCAL_ONLY'
                  END,
                  updated_at=clock_timestamp()
                FROM eatwhat_file.file_revision fr
                WHERE p.file_id=? AND p.revision=? AND fr.file_id=p.file_id AND fr.revision=p.revision
                """, fileId, revision);
        if (updated == 0) {
            // A fresh row has no peer confirmation, so it can never start as AVAILABLE_GLOBAL.
            jdbc.update("""
                    INSERT INTO eatwhat_file.file_publication(file_id, revision, global_state, created_at, updated_at)
                    SELECT fr.file_id, fr.revision,
                      CASE
                        WHEN EXISTS(SELECT 1 FROM eatwhat_file.file_replica r
                                    WHERE r.file_id=fr.file_id AND r.revision=fr.revision AND r.state='PAUSED_CONSENT')
                        THEN 'PAUSED'
                        WHEN EXISTS(SELECT 1 FROM eatwhat_file.file_replica r
                                    WHERE r.file_id=fr.file_id AND r.revision=fr.revision AND r.site='PEER')
                        THEN 'PEER_PENDING'
                        ELSE 'LOCAL_ONLY'
                      END,
                      clock_timestamp(), clock_timestamp()
                    FROM eatwhat_file.file_revision fr
                    WHERE fr.file_id=? AND fr.revision=?
                    ON CONFLICT(file_id, revision) DO NOTHING
                    """, fileId, revision);
        }
    }

    /**
     * Mark a peer revision as confirmed by the peer during catalog ingestion.
     * Sets peer_confirmed_at to current timestamp if null, then recomputes state.
     * Called in the same transaction as replica/job inserts.
     *
     * @param fileId file UUID
     * @param revision file revision number
     */
    public void markPeerConfirmed(UUID fileId, int revision) {
        Objects.requireNonNull(fileId, "fileId");
        jdbc.update("""
                UPDATE eatwhat_file.file_publication
                SET peer_confirmed_at=clock_timestamp()
                WHERE file_id=? AND revision=? AND peer_confirmed_at IS NULL
                """, fileId, revision);
        recompute(fileId, revision);
    }
}
