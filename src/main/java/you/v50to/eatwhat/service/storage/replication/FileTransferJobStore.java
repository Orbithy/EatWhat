package you.v50to.eatwhat.service.storage.replication;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

/**
 * Manages file transfer job lifecycle with optimistic fencing and database-clock-only leasing.
 * All operations use database clock (clock_timestamp()) for timestamp evaluation.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.storage.files.replication.enabled", havingValue = "true")
public class FileTransferJobStore {

    private final JdbcTemplate jdbc;
    private final FilePublicationService publicationService;

    public FileTransferJobStore(JdbcTemplate jdbc, FilePublicationService publicationService) {
        this.jdbc = Objects.requireNonNull(jdbc, "JdbcTemplate");
        this.publicationService = Objects.requireNonNull(publicationService, "FilePublicationService");
    }

    /**
     * Job record representing a single file transfer task.
     */
    public record Job(
        UUID jobId,
        UUID fileId,
        int revision,
        String kind,
        String targetSite,
        int attempts,
        long fencingToken,
        Long consentEpoch,
        // Revision fields
        String sha256,
        long sizeBytes,
        String contentType,
        String finalKey,
        // Owner
        UUID ownerGid
    ) { }

    /** A dedicated transaction holds the I/O fence across synchronous object-store calls, never business rows. */
    public static final class IoFence implements AutoCloseable {
        private final java.sql.Connection connection;
        private IoFence(java.sql.Connection connection) { this.connection=connection; }
        @Override public void close() {
            try { connection.rollback(); } catch(java.sql.SQLException failure) { throw new IllegalStateException("FILE_IO_FENCE_RELEASE",failure); }
            finally { try { connection.close(); } catch(java.sql.SQLException failure) { throw new IllegalStateException("FILE_IO_FENCE_CLOSE",failure); } }
        }
    }
    public IoFence acquireIoFence(Job job) {
        java.sql.Connection connection=null;
        try {
            connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection();connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("SELECT pg_try_advisory_xact_lock(hashtextextended('file-io:'||?::text||':'||?::text,0))")) {
                statement.setString(1,job.fileId().toString());statement.setString(2,Integer.toString(job.revision()));
                try(var result=statement.executeQuery()) {
                    if(!result.next()||!result.getBoolean(1)) throw new IllegalStateException("FILE_IO_BUSY");
                }
            }
            return new IoFence(connection);
        } catch(java.sql.SQLException|RuntimeException failure) {
            if(connection!=null) {try {connection.rollback();connection.close();} catch(java.sql.SQLException close) {failure.addSuppressed(close);}}
            throw new IllegalStateException("FILE_IO_FENCE_UNAVAILABLE",failure);
        }
    }
    public boolean isDeletionFrozen(Job job) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_file.file_gc_coordination WHERE file_id=? AND revision=? AND state<>'REQUESTED') OR EXISTS(SELECT 1 FROM eatwhat_cluster.gc_fence WHERE file_id=? AND revision=?)",Boolean.class,job.fileId(),job.revision(),job.fileId(),job.revision()));
    }

    /**
     * Claim ready or expired-leased jobs for processing.
     * Uses a CTE with FOR UPDATE SKIP LOCKED to efficiently select and lock candidates,
     * then updates them atomically with incremented fencing_token and attempts.
     *
     * @param batch maximum number of jobs to claim
     * @param lease duration to hold the lease
     * @return list of jobs ready for processing
     */
    public List<Job> claim(int batch, Duration lease) {
        if (batch < 1 || batch > 500) {
            throw new IllegalArgumentException("Batch size must be between 1 and 500");
        }
        long leaseMillis = durationMillis(lease, 900_000); // max 15 minutes
        return jdbc.query("""
                WITH candidates AS (
                  SELECT j.job_id FROM eatwhat_file.file_transfer_job j
                  WHERE (j.status='READY' AND j.next_attempt_at<=clock_timestamp())
                     OR (j.status='LEASED' AND j.lease_until<=clock_timestamp())
                  ORDER BY j.next_attempt_at, j.job_id
                  LIMIT ? FOR UPDATE OF j SKIP LOCKED
                ),
                leased AS (
                  UPDATE eatwhat_file.file_transfer_job j SET
                    status='LEASED',
                    attempts=j.attempts+1,
                    fencing_token=j.fencing_token+1,
                    lease_until=clock_timestamp()+(?::bigint * interval '1 millisecond'),
                    updated_at=clock_timestamp()
                  FROM candidates c
                  WHERE j.job_id=c.job_id
                  RETURNING j.job_id, j.file_id, j.revision, j.kind, j.target_site, j.attempts, j.fencing_token, j.consent_epoch
                )
                SELECT l.job_id, l.file_id, l.revision, l.kind, l.target_site, l.attempts, l.fencing_token, l.consent_epoch,
                  fr.sha256, fr.size_bytes, fr.content_type, fr.final_key,
                  fo.owner_gid
                FROM leased l
                JOIN eatwhat_file.file_revision fr ON fr.file_id=l.file_id AND fr.revision=l.revision
                JOIN eatwhat_file.file_object fo ON fo.file_id=l.file_id
                ORDER BY l.job_id
                """, (row, number) -> new Job(
                    row.getObject(1, UUID.class),
                    row.getObject(2, UUID.class),
                    row.getInt(3),
                    row.getString(4),
                    row.getString(5),
                    row.getInt(6),
                    row.getLong(7),
                    row.getObject(8, Long.class),
                    row.getString(9),
                    row.getLong(10),
                    row.getString(11),
                    row.getString(12),
                    row.getObject(13, UUID.class)
                ),
                batch, leaseMillis);
    }

    /**
     * True when the LOCAL replica of the job's revision was physically deleted by the GC. A COPY_TO_PEER job has no
     * source bytes left in that case and must not be retried.
     */
    public boolean isLocalSourceDeleted(Job job) {
        Objects.requireNonNull(job, "job");
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_file.file_replica
                              WHERE file_id=? AND revision=? AND site='LOCAL' AND state IN ('DELETING','DELETED'))
                """, Boolean.class, job.fileId(), job.revision()));
    }

    public boolean isPeerSourceDeleted(Job job) {
        Objects.requireNonNull(job,"job");
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_file.file_replica WHERE file_id=? AND revision=? AND site='PEER' AND state IN ('DELETING','DELETED'))",Boolean.class,job.fileId(),job.revision()));
    }

    /**
     * True when the replica this job would write (the TARGET site's replica of its revision) is DELETING or DELETED:
     * the transfer must not be recorded as completed against a replica the GC removed.
     */
    public boolean isTargetReplicaDeleted(Job job) {
        Objects.requireNonNull(job, "job");
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_file.file_replica
                              WHERE file_id=? AND revision=? AND site=? AND state IN ('DELETING','DELETED'))
                """, Boolean.class, job.fileId(), job.revision(), job.targetSite()));
    }

    /**
     * Extend the lease on a held job by resetting lease_until to the future.
     * Returns false if the job is not currently held by this holder (stale token or expired).
     *
     * @param job the job held by this worker
     * @param lease new lease duration
     * @return true if extended, false if stale or released
     */
    public boolean extendLease(Job job, Duration lease) {
        Objects.requireNonNull(job, "job");
        long leaseMillis = durationMillis(lease, 900_000);
        int changed = jdbc.update("""
                UPDATE eatwhat_file.file_transfer_job
                SET lease_until=clock_timestamp()+(?::bigint * interval '1 millisecond'),
                    updated_at=clock_timestamp()
                WHERE job_id=? AND status='LEASED' AND fencing_token=? AND lease_until>clock_timestamp()
                """, leaseMillis, job.jobId, job.fencingToken);
        return changed == 1;
    }

    /**
     * Record the id of the multipart upload this holder just started, so the stale-multipart sweep knows the upload
     * is owned. Fenced exactly like every other write: it changes nothing (returns false) unless the job is LEASED
     * with this holder's fencing token and an unexpired lease.
     *
     * @return true if recorded, false if the lease was lost
     */
    public boolean setMultipartUpload(Job job, String uploadId) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(uploadId, "uploadId");
        int changed = jdbc.update("""
                UPDATE eatwhat_file.file_transfer_job
                SET multipart_upload_id=?,
                    updated_at=clock_timestamp()
                WHERE job_id=? AND status='LEASED' AND fencing_token=? AND lease_until>clock_timestamp()
                """, uploadId, job.jobId, job.fencingToken);
        return changed == 1;
    }

    /**
     * Forget the multipart upload id after the upload completed or was aborted. Same fencing as every other write.
     *
     * @return true if cleared, false if the lease was lost (the new holder owns the column now)
     */
    public boolean clearMultipartUpload(Job job) {
        Objects.requireNonNull(job, "job");
        int changed = jdbc.update("""
                UPDATE eatwhat_file.file_transfer_job
                SET multipart_upload_id=NULL,
                    updated_at=clock_timestamp()
                WHERE job_id=? AND status='LEASED' AND fencing_token=? AND lease_until>clock_timestamp()
                """, job.jobId, job.fencingToken);
        return changed == 1;
    }

    /**
     * Retry a failed job by resetting its status to READY and scheduling a future attempt.
     * Returns false if the job is not currently held by this holder (stale token or expired).
     *
     * @param job the job held by this worker
     * @param delay backoff duration before next attempt
     * @param errorCode error classification (≤64 chars)
     * @param detail diagnostic message (≤512 chars)
     * @return true if reset, false if stale or released
     */
    public boolean retry(Job job, Duration delay, String errorCode, String detail) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(errorCode, "errorCode");
        if (errorCode.isBlank() || errorCode.length() > 64) {
            throw new IllegalArgumentException("Error code must be 1-64 chars");
        }
        if (detail != null && detail.length() > 512) {
            throw new IllegalArgumentException("Detail must be ≤512 chars");
        }
        long delayMillis = durationMillis(delay, 1_800_000); // max 30 minutes
        int changed = jdbc.update("""
                UPDATE eatwhat_file.file_transfer_job
                SET status='READY',
                    next_attempt_at=clock_timestamp()+(?::bigint * interval '1 millisecond'),
                    lease_until=NULL,
                    multipart_upload_id=NULL,
                    last_error=?,
                    last_error_detail=?,
                    updated_at=clock_timestamp()
                WHERE job_id=? AND status='LEASED' AND fencing_token=? AND lease_until>clock_timestamp()
                """, delayMillis, errorCode, detail, job.jobId, job.fencingToken);
        return changed == 1;
    }

    /**
     * Quarantine a job that cannot be recovered.
     * Also writes one alert row to file_replica_alert and sets status to QUARANTINED.
     * Both operations occur atomically in this transaction.
     *
     * @param job the job held by this worker
     * @param code alert type (will be truncated to 32 chars)
     * @param message alert detail message
     * @return true if quarantined, false if stale or released
     */
    public boolean quarantine(Job job, String code, String message) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        if (code.isBlank()) {
            throw new IllegalArgumentException("Alert type code is required");
        }
        String alertType = code.length() > 32 ? code.substring(0, 32) : code;
        return jdbc.queryForObject("""
                WITH job_update AS (
                  UPDATE eatwhat_file.file_transfer_job
                  SET status='QUARANTINED',
                      lease_until=NULL,
                      multipart_upload_id=NULL,
                      updated_at=clock_timestamp()
                  WHERE job_id=? AND status='LEASED' AND fencing_token=? AND lease_until>clock_timestamp()
                  RETURNING 1
                ),
                alert_insert AS (
                  INSERT INTO eatwhat_file.file_replica_alert(alert_id, file_id, revision, site, alert_type, message, created_at)
                  VALUES(?, ?, ?, ?, ?, ?, clock_timestamp())
                  RETURNING 1
                )
                SELECT EXISTS(SELECT 1 FROM job_update) AND EXISTS(SELECT 1 FROM alert_insert)
                """, Boolean.class,
                job.jobId, job.fencingToken,
                UUID.randomUUID(), job.fileId, job.revision, job.targetSite, alertType, message);
    }

    /**
     * Pause a job due to consent withdrawal or revocation.
     * Returns false if the job is not currently held by this holder (stale token or expired).
     *
     * @param job the job held by this worker
     * @return true if paused, false if stale or released
     */
    public boolean pauseConsent(Job job) {
        Objects.requireNonNull(job, "job");
        int changed = jdbc.update("""
                UPDATE eatwhat_file.file_transfer_job
                SET status='PAUSED_CONSENT',
                    lease_until=NULL,
                    multipart_upload_id=NULL,
                    updated_at=clock_timestamp()
                WHERE job_id=? AND status='LEASED' AND fencing_token=? AND lease_until>clock_timestamp()
                """, job.jobId, job.fencingToken);
        return changed == 1;
    }

    /**
     * Write an alert row for a transfer job without changing job status.
     * Used for retry exhaustion and other non-fatal issues.
     *
     * @param job the job to alert for
     * @param alertType alert type classification (≤32 chars)
     * @param message descriptive message
     * @return true if alert was written, false otherwise
     */
    public boolean alert(Job job, String alertType, String message) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(alertType, "alertType");
        Objects.requireNonNull(message, "message");
        if (alertType.isBlank()) {
            throw new IllegalArgumentException("Alert type code is required");
        }
        String type = alertType.length() > 32 ? alertType.substring(0, 32) : alertType;
        int inserted = jdbc.update("""
                INSERT INTO eatwhat_file.file_replica_alert(alert_id, file_id, revision, site, alert_type, message, created_at)
                VALUES(?, ?, ?, ?, ?, ?, clock_timestamp())
                """,
                UUID.randomUUID(), job.fileId, job.revision, job.targetSite, type, message);
        return inserted == 1;
    }

    /**
     * Mark a job as successfully completed.
     * Atomically updates the job status and upserts a file_replica row for the target site.
     * Then recomputes file_publication state in the same transaction.
     * Returns false if the job is not currently held by this holder (stale token or expired).
     *
     * @param job the job held by this worker
     * @param verified digest result from post-upload verification
     * @return true if completed and replica record upserted, false if stale or released
     */
    public boolean complete(Job job, ReplicaObjectStore.DigestResult verified) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(verified, "verified");

        String state = job.targetSite.equals("LOCAL") ? "AVAILABLE_LOCAL" : "PEER_VERIFIED";

        boolean completed = jdbc.queryForObject("""
                WITH job_update AS (
                  UPDATE eatwhat_file.file_transfer_job
                  SET status='DONE',
                      completed_at=clock_timestamp(),
                      lease_until=NULL,
                      multipart_upload_id=NULL,
                      updated_at=clock_timestamp()
                  WHERE job_id=? AND status='LEASED' AND fencing_token=? AND lease_until>clock_timestamp()
                    AND NOT EXISTS (SELECT 1 FROM eatwhat_file.file_replica d
                                    WHERE d.file_id=? AND d.revision=? AND d.site=? AND d.state IN ('DELETING','DELETED'))
                  RETURNING 1
                ),
                replica_upsert AS (
                  INSERT INTO eatwhat_file.file_replica(file_id, revision, site, object_key, state, verified_sha256, verified_size, verified_at, updated_at)
                  SELECT ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), clock_timestamp() FROM job_update
                  ON CONFLICT(file_id, revision, site) DO UPDATE SET
                    state=EXCLUDED.state,
                    verified_sha256=EXCLUDED.verified_sha256,
                    verified_size=EXCLUDED.verified_size,
                    verified_at=EXCLUDED.verified_at,
                    updated_at=clock_timestamp()
                  WHERE file_replica.state NOT IN ('DELETING','DELETED')
                  RETURNING 1
                )
                SELECT EXISTS(SELECT 1 FROM job_update) AND EXISTS(SELECT 1 FROM replica_upsert)
                """, Boolean.class,
                job.jobId, job.fencingToken,
                job.fileId, job.revision, job.targetSite,
                job.fileId, job.revision, job.targetSite, job.finalKey, state, verified.sha256Hex(), verified.size());

        // Recompute publication state in the same transaction if job was completed
        if (completed) {
            publicationService.recompute(job.fileId, job.revision);
        }

        return completed;
    }

    /**
     * Enqueue a new transfer job for a given file revision and target site.
     * If a job already exists for this combination, this is a no-op (ON CONFLICT DO NOTHING).
     *
     * @param fileId file UUID
     * @param revision file revision number
     * @param kind job kind: COPY_TO_PEER or PULL_FROM_PEER
     * @param targetSite target site: LOCAL or PEER
     * @param consentEpoch consent timestamp or null
     * @return job UUID (may be newly created or existing)
     */
    public UUID enqueue(UUID fileId, int revision, String kind, String targetSite, Long consentEpoch) {
        Objects.requireNonNull(fileId, "fileId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(targetSite, "targetSite");
        if (!kind.matches("COPY_TO_PEER|PULL_FROM_PEER")) {
            throw new IllegalArgumentException("Invalid kind");
        }
        if (!targetSite.matches("LOCAL|PEER")) {
            throw new IllegalArgumentException("Invalid targetSite");
        }

        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO eatwhat_file.file_transfer_job
                  (job_id, file_id, revision, kind, target_site, status, attempts, next_attempt_at, consent_epoch, created_at, updated_at)
                VALUES(?, ?, ?, ?, ?, 'READY', 0, clock_timestamp(), ?, clock_timestamp(), clock_timestamp())
                ON CONFLICT(file_id, revision, target_site) DO NOTHING
                """, jobId, fileId, revision, kind, targetSite, consentEpoch);
        return jobId;
    }

    /**
     * Compute exponential backoff with jitter.
     * Formula: min(max, base * 2^(attempts-1)) ± jitter
     *
     * @param attempts number of attempts so far (1-based)
     * @param base initial retry delay
     * @param max maximum delay cap
     * @param jitterFraction jitter as fraction of computed delay (0.0-1.0)
     * @return backoff duration
     */
    static Duration backoff(int attempts, Duration base, Duration max, double jitterFraction) {
        return backoff(attempts, base, max, jitterFraction, new Random());
    }

    /**
     * Compute exponential backoff with jitter using provided RandomGenerator.
     * Formula: min(max, base * 2^(attempts-1)) ± jitter
     *
     * @param attempts number of attempts so far (1-based)
     * @param base initial retry delay
     * @param max maximum delay cap
     * @param jitterFraction jitter as fraction of computed delay (0.0-1.0)
     * @param rng RandomGenerator for jitter calculation
     * @return backoff duration
     */
    static Duration backoff(int attempts, Duration base, Duration max, double jitterFraction, Random rng) {
        if (attempts < 1) throw new IllegalArgumentException("Attempts must be ≥1");
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(max, "max");
        Objects.requireNonNull(rng, "rng");
        if (jitterFraction < 0 || jitterFraction > 1) {
            throw new IllegalArgumentException("Jitter fraction must be 0.0-1.0");
        }

        // Exponential: base * 2^(attempts-1)
        long baseMillis = base.toMillis();
        long delayMillis = baseMillis;
        for (int i = 1; i < attempts; i++) {
            delayMillis = Math.min(delayMillis * 2, max.toMillis());
        }
        delayMillis = Math.min(delayMillis, max.toMillis());

        // Apply jitter
        if (jitterFraction > 0) {
            long jitterRange = (long) (delayMillis * jitterFraction);
            long jitterAmount = rng.nextLong(2 * jitterRange + 1) - jitterRange;
            delayMillis = Math.max(0, delayMillis + jitterAmount);
        }

        return Duration.ofMillis(delayMillis);
    }

    private static long durationMillis(Duration duration, long maxMillis) {
        if (duration == null || duration.isNegative() || duration.toMillis() > maxMillis) {
            throw new IllegalArgumentException("Duration is out of range");
        }
        return duration.toMillis();
    }
}
