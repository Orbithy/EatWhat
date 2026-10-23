package you.v50to.eatwhat.service.storage.replication;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Queue access for CDN invalidation tasks: claim with FOR UPDATE SKIP LOCKED, and fenced writes only
 * (every write carries {@code status='LEASED' AND fencing_token=? AND lease_until>clock_timestamp()}; a stale holder
 * changes nothing). The database clock is the only clock. Same idioms as {@link FileTransferJobStore}.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.storage.files.cdn-invalidation.enabled", havingValue = "true")
public class FileCdnInvalidationStore {
    public static final String ALERT_RETRY_EXHAUSTED = "CDN_RETRY_EXHAUSTED";
    public static final String ALERT_FAILED = "CDN_INVALIDATION_FAILED";

    /** Leading space matters: text blocks strip the trailing space of the line it is appended to. */
    private static final String HELD = " status='LEASED' AND fencing_token=? AND lease_until>clock_timestamp()";

    private final JdbcTemplate jdbc;

    public FileCdnInvalidationStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "JdbcTemplate");
    }

    /** A claimed task. It carries ids and the revision's final key only: never a URL or a path. */
    public record Task(UUID taskId, UUID fileId, int revision, String reason, int generation, int attempts,
                       long fencingToken, String finalKey) { }

    /** Claim READY (due) and expired-LEASED tasks: attempts+1, fencing_token+1, lease on the DB clock. */
    public List<Task> claim(int batch, Duration lease) {
        if (batch < 1 || batch > 500) {
            throw new IllegalArgumentException("Batch size must be between 1 and 500");
        }
        long leaseMillis = lease.toMillis();
        return jdbc.query("""
                WITH candidates AS (
                  SELECT t.task_id FROM eatwhat_file.file_cdn_invalidation t
                  WHERE (t.status='READY' AND t.next_attempt_at<=clock_timestamp())
                     OR (t.status='LEASED' AND t.lease_until<=clock_timestamp())
                  ORDER BY t.next_attempt_at, t.task_id
                  LIMIT ? FOR UPDATE OF t SKIP LOCKED
                ),
                leased AS (
                  UPDATE eatwhat_file.file_cdn_invalidation t SET
                    status='LEASED',
                    attempts=t.attempts+1,
                    fencing_token=t.fencing_token+1,
                    lease_until=clock_timestamp()+(?::bigint * interval '1 millisecond'),
                    updated_at=clock_timestamp()
                  FROM candidates c
                  WHERE t.task_id=c.task_id
                  RETURNING t.task_id, t.file_id, t.revision, t.reason, t.generation, t.attempts, t.fencing_token
                )
                SELECT l.task_id, l.file_id, l.revision, l.reason, l.generation, l.attempts, l.fencing_token, fr.final_key
                FROM leased l
                JOIN eatwhat_file.file_revision fr ON fr.file_id=l.file_id AND fr.revision=l.revision
                ORDER BY l.task_id
                """, (rs, n) -> new Task(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3),
                        rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getLong(7), rs.getString(8)),
                batch, leaseMillis);
    }

    /** True when any alias of the revision has a live PUBLIC reference again (the revoke is moot). */
    public boolean hasLivePublicReference(Task task) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM eatwhat_file.file_reference_live f
                              JOIN eatwhat_file.file_key_alias a ON a.logical_key=f.logical_key
                              WHERE a.file_id=? AND a.revision=? AND f.visibility='PUBLIC')
                """, Boolean.class, task.fileId(), task.revision()));
    }

    /**
     * The "revived" decision and the SKIPPED write as ONE statement: skips only while a live PUBLIC reference exists at
     * the moment of the write and the lease is still held. A release that commits afterwards enqueues its own newer
     * task (the trigger only dedupes against READY tasks), so no revoke can be lost between check and write.
     *
     * @return true when the task was skipped as REVIVED; false when it is stale or the revision is not publicly referenced
     */
    public boolean skipIfRevived(Task task) {
        return jdbc.update("""
                UPDATE eatwhat_file.file_cdn_invalidation t
                SET status='SKIPPED', skip_reason='REVIVED', lease_until=NULL,
                    completed_at=clock_timestamp(), updated_at=clock_timestamp()
                WHERE t.task_id=? AND""" + HELD + """
                  AND EXISTS (SELECT 1 FROM eatwhat_file.file_reference_live f
                              JOIN eatwhat_file.file_key_alias a ON a.logical_key=f.logical_key
                              WHERE a.file_id=t.file_id AND a.revision=t.revision AND f.visibility='PUBLIC')
                """, task.taskId(), task.fencingToken()) == 1;
    }

    public boolean markDone(Task task, String providerRequestId) {
        return jdbc.update("""
                UPDATE eatwhat_file.file_cdn_invalidation
                SET status='DONE', provider_request_id=?, last_error=NULL, lease_until=NULL,
                    completed_at=clock_timestamp(), updated_at=clock_timestamp()
                WHERE task_id=? AND """ + HELD, providerRequestId, task.taskId(), task.fencingToken()) == 1;
    }

    public boolean markSkipped(Task task, String skipReason) {
        return jdbc.update("""
                UPDATE eatwhat_file.file_cdn_invalidation
                SET status='SKIPPED', skip_reason=?, lease_until=NULL,
                    completed_at=clock_timestamp(), updated_at=clock_timestamp()
                WHERE task_id=? AND """ + HELD, skipReason, task.taskId(), task.fencingToken()) == 1;
    }

    /** Back to READY with a future next_attempt_at. */
    public boolean retry(Task task, Duration delay, String code) {
        return jdbc.update("""
                UPDATE eatwhat_file.file_cdn_invalidation
                SET status='READY', lease_until=NULL, last_error=?,
                    next_attempt_at=clock_timestamp()+(?::bigint * interval '1 millisecond'),
                    updated_at=clock_timestamp()
                WHERE task_id=? AND """ + HELD, code, delay.toMillis(), task.taskId(), task.fencingToken()) == 1;
    }

    /** QUARANTINED plus one CDN_INVALIDATION_FAILED alert, atomically. */
    public boolean quarantine(Task task, String code) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                WITH quarantined AS (
                  UPDATE eatwhat_file.file_cdn_invalidation
                  SET status='QUARANTINED', last_error=?, lease_until=NULL, updated_at=clock_timestamp()
                  WHERE task_id=? AND """ + HELD + """
                  RETURNING 1
                ),
                alert AS (
                  INSERT INTO eatwhat_file.file_replica_alert(alert_id, file_id, revision, site, alert_type, message, created_at)
                  SELECT ?, ?, ?, 'LOCAL', ?, ?, clock_timestamp() WHERE EXISTS (SELECT 1 FROM quarantined)
                  RETURNING 1
                )
                SELECT EXISTS(SELECT 1 FROM quarantined)
                """, Boolean.class, code, task.taskId(), task.fencingToken(),
                UUID.randomUUID(), task.fileId(), task.revision(), ALERT_FAILED,
                "CDN invalidation task " + task.taskId() + " failed permanently: " + code));
    }

    /** ONE alert per task when retries reach the threshold; the task itself keeps retrying. */
    public boolean alertRetryExhausted(Task task) {
        String message = "CDN invalidation task " + task.taskId() + " is still failing after " + task.attempts() + " attempts";
        return jdbc.update("""
                INSERT INTO eatwhat_file.file_replica_alert(alert_id, file_id, revision, site, alert_type, message, created_at)
                SELECT ?, ?, ?, 'LOCAL', ?, ?, clock_timestamp()
                WHERE EXISTS (SELECT 1 FROM eatwhat_file.file_cdn_invalidation WHERE task_id=? AND """ + HELD + """
                )
                  AND NOT EXISTS (SELECT 1 FROM eatwhat_file.file_replica_alert
                                  WHERE file_id=? AND revision=? AND alert_type=? AND message LIKE ?)
                """, UUID.randomUUID(), task.fileId(), task.revision(), ALERT_RETRY_EXHAUSTED, message,
                task.taskId(), task.fencingToken(),
                task.fileId(), task.revision(), ALERT_RETRY_EXHAUSTED, "%" + task.taskId() + "%") == 1;
    }
}
