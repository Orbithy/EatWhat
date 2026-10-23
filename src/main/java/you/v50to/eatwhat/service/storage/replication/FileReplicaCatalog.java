package you.v50to.eatwhat.service.storage.replication;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Catalogs peer revisions sent from remote sites.
 * Idempotently records metadata and enqueues pull-from-peer jobs.
 * All operations are append-only and immutable (via triggers).
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.storage.files.replication.enabled", havingValue = "true")
public class FileReplicaCatalog {

    private final JdbcTemplate jdbc;
    private final you.v50to.eatwhat.cluster.ClusterMembershipView membership;

    public FileReplicaCatalog(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "JdbcTemplate");
        this.membership=new you.v50to.eatwhat.cluster.ClusterMembershipView(jdbc);
    }

    /**
     * Metadata about a peer revision received from remote site.
     */
    public record PeerRevision(
            UUID fileId,
            int revision,
            UUID ownerGid,
            String biz,
            String logicalKey,
            String finalKey,
            String sha256,
            long sizeBytes,
            String contentType
    ) { }

    /**
     * Result of recording a peer revision.
     */
    public enum Result {
        /** New revision recorded successfully */
        RECORDED,
        /** Revision already recorded with identical sha256 and size (idempotent) */
        ALREADY_RECORDED,
        /** Revision conflicts: same (fileId,revision) but different sha256 or size */
        CONFLICT
    }

    /**
     * Atomically record a peer revision in one transaction.
     * Idempotent on identical sha256+size; returns CONFLICT if metadata differs.
     *
     * Inserts in this order:
     * 1. file_object (if absent) - identity immutable
     * 2. file_revision (state VERIFIED, immutable) - fails if replica detected before
     * 3. file_key_alias - identity immutable
     * 4. LOCAL replica (PENDING_SYNC) - for incoming verification
     * 5. PEER replica (PENDING_SYNC) - for source tracking
     * 6. file_publication (PEER_PENDING, peer_confirmed_at=now) - metadata already attested
     * 7. file_transfer_job PULL_FROM_PEER for LOCAL target
     *
     * On CONFLICT(file_id,revision): if sha256+size match exactly, treat as already-recorded;
     * otherwise create file_replica_alert(site=PEER, alert_type=REVISION_MISMATCH) and return CONFLICT.
     *
     * @param peer revision metadata from remote site
     * @return RECORDED, ALREADY_RECORDED, or CONFLICT
     */
    @Transactional
    public Result recordPeerRevision(PeerRevision peer) {
        Objects.requireNonNull(peer, "peer");

        try {
            // Step 1: Insert file_object if absent
            jdbc.update("""
                    INSERT INTO eatwhat_file.file_object(file_id, owner_gid, biz, logical_key, canonical_revision, created_at)
                    VALUES(?, ?, ?, ?, 1, clock_timestamp())
                    ON CONFLICT DO NOTHING
                    """, peer.fileId, peer.ownerGid, peer.biz, peer.logicalKey);

            var identity=jdbc.queryForMap("SELECT owner_gid,biz,logical_key,canonical_revision FROM eatwhat_file.file_object WHERE file_id=?",peer.fileId);
            if(!peer.ownerGid.equals(identity.get("owner_gid"))||!peer.biz.equals(identity.get("biz"))
                    ||!peer.logicalKey.equals(identity.get("logical_key"))) throw new IllegalArgumentException("FILE_IDENTITY_CONFLICT");
            var aliases=jdbc.queryForList("SELECT file_id,revision,owner_gid,biz FROM eatwhat_file.file_key_alias WHERE logical_key=?",peer.logicalKey);
            if(!aliases.isEmpty()) {
                var a=aliases.getFirst();
                if(!peer.fileId.equals(a.get("file_id"))||peer.revision!=((Number)a.get("revision")).intValue()
                        ||!peer.ownerGid.equals(a.get("owner_gid"))||!peer.biz.equals(a.get("biz"))) throw new IllegalArgumentException("FILE_ALIAS_CONFLICT");
            }
            // Step 2: Check if revision already exists and if so, verify sha256/size
            var existing = jdbc.query("""
                    SELECT sha256, size_bytes,final_key,content_type FROM eatwhat_file.file_revision
                    WHERE file_id=? AND revision=?
                    """,
                    (rs, rowNum) -> new RevisionMetadata(rs.getString(1).trim(), rs.getLong(2),rs.getString(3),rs.getString(4)),
                    peer.fileId, peer.revision);

            if (!existing.isEmpty()) {
                RevisionMetadata existing_meta = existing.getFirst();
                if (existing_meta.sha256.equals(peer.sha256) && existing_meta.sizeBytes == peer.sizeBytes && existing_meta.finalKey.equals(peer.finalKey) && existing_meta.contentType.equals(peer.contentType)) {
                    // Identical metadata: idempotent
                    log.debug("Peer revision {} already recorded identically", peer.fileId + ":" + peer.revision);
                    return Result.ALREADY_RECORDED;
                } else {
                    // Metadata conflict: record alert and return CONFLICT
                    jdbc.update("""
                            INSERT INTO eatwhat_file.file_replica_alert(alert_id, file_id, revision, site, alert_type, message, created_at)
                            VALUES(?, ?, ?, 'PEER', 'REVISION_MISMATCH', ?, clock_timestamp())
                            """,
                            UUID.randomUUID(),
                            peer.fileId, peer.revision,
                            String.format("Expected sha256=%s size=%d, got sha256=%s size=%d",
                                    existing_meta.sha256, existing_meta.sizeBytes,
                                    peer.sha256, peer.sizeBytes));
                    log.warn("Peer revision conflict for {} revision {}: sha256/size mismatch", peer.fileId, peer.revision);
                    return Result.CONFLICT;
                }
            }

            // Step 3: Insert file_revision (immutable)
            jdbc.update("""
                    INSERT INTO eatwhat_file.file_revision
                      (file_id, revision, final_key, sha256, size_bytes, content_type, state, verified_at, created_at)
                    VALUES(?, ?, ?, ?, ?, ?, 'VERIFIED', clock_timestamp(), clock_timestamp())
                    """,
                    peer.fileId, peer.revision, peer.finalKey, peer.sha256, peer.sizeBytes, peer.contentType);

            // Step 4: Insert file_key_alias (immutable)
            jdbc.update("""
                    INSERT INTO eatwhat_file.file_key_alias(logical_key, file_id, revision, owner_gid, biz, created_at)
                    VALUES(?, ?, ?, ?, ?, clock_timestamp())
                    ON CONFLICT DO NOTHING
                    """,
                    peer.logicalKey, peer.fileId, peer.revision, peer.ownerGid, peer.biz);

            // Step 5: Insert LOCAL replica (PENDING_SYNC, to be pulled from PEER)
            jdbc.update("""
                    INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, updated_at)
                    VALUES(?, ?, 'LOCAL', ?, 'PENDING_SYNC', clock_timestamp())
                    ON CONFLICT DO NOTHING
                    """,
                    peer.fileId, peer.revision, peer.finalKey);

            // Step 6: Insert PEER replica (PENDING_SYNC, as received)
            if(membership.bound()){
                jdbc.update("INSERT INTO eatwhat_file.file_publication(file_id,revision,global_state,peer_confirmed_at) VALUES(?,?,'PEER_PENDING',clock_timestamp()) ON CONFLICT DO NOTHING",peer.fileId,peer.revision);return Result.RECORDED;
            }
            jdbc.update("""
                    INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at, updated_at)
                    VALUES(?, ?, 'PEER', ?, 'PENDING_SYNC', ?, ?, clock_timestamp(), clock_timestamp())
                    ON CONFLICT DO NOTHING
                    """,
                    peer.fileId, peer.revision, peer.finalKey, peer.sha256, peer.sizeBytes);

            // Step 7: Insert publication (peer attests metadata, so peer_confirmed_at is set)
            jdbc.update("""
                    INSERT INTO eatwhat_file.file_publication
                      (file_id, revision, global_state, peer_confirmed_at, created_at, updated_at)
                    VALUES(?, ?, 'PEER_PENDING', clock_timestamp(), clock_timestamp(), clock_timestamp())
                    ON CONFLICT DO NOTHING
                    """,
                    peer.fileId, peer.revision);

            // Step 8: Insert PULL_FROM_PEER job (target_site='LOCAL')
            UUID jobId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO eatwhat_file.file_transfer_job
                      (job_id, file_id, revision, kind, target_site, status, attempts, next_attempt_at, created_at, updated_at)
                    VALUES(?, ?, ?, 'PULL_FROM_PEER', 'LOCAL', 'READY', 0, clock_timestamp(), clock_timestamp(), clock_timestamp())
                    ON CONFLICT(file_id, revision, target_site) DO NOTHING
                    """,
                    jobId, peer.fileId, peer.revision);

            log.debug("Recorded peer revision {} from {}", peer.fileId + ":" + peer.revision, peer.biz);
            return Result.RECORDED;

        } catch (Exception e) {
            // Log the error but do not re-throw; caller can check for exceptions or handle gracefully
            log.error("Failed to record peer revision {}: {}", peer.fileId + ":" + peer.revision, e.getMessage(), e);
            throw e;
        }
    }

    private record RevisionMetadata(String sha256, long sizeBytes,String finalKey,String contentType) { }
}
