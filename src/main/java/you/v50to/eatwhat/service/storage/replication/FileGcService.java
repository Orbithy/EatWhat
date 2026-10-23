package you.v50to.eatwhat.service.storage.replication;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import you.v50to.eatwhat.config.FileGcProperties;
import you.v50to.eatwhat.config.S3StorageProperties;
import you.v50to.eatwhat.config.SiteProperties;

import you.v50to.eatwhat.sync.file.FileGcProtocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Physical garbage collector for the LOCAL object store. THIS CLASS CAN DELETE OBJECTS. Every rule below is a hard
 * gate: when in doubt the candidate is BLOCKED (or the run stays a dry run) and nothing is deleted.
 *
 * <h2>Phases</h2>
 * <ol>
 *   <li>{@link #detect()} upserts {@code file_gc_candidate} rows (state DETECTED) from database facts. It never
 *       downgrades a DELETED or KEPT row and only re-opens a REVIVED row.</li>
 *   <li>{@link #run()} evaluates and acts on each candidate in its own database transaction that
 *       (a) claims the row with a fencing lease, (b) locks the business row that guards the object,
 *       (c) re-reads every fact in fresh statements and evaluates the rules, and (d) in EXECUTE mode applies
 *       the database changes first and performs the physical delete as the very last step before commit.</li>
 * </ol>
 *
 * <h2>Two-phase delete (UNREFERENCED_REVISION)</h2>
 * Phase 1 commits replica and candidate as DELETING (under the locks, after the full evaluation). The object delete and
 * the HEAD confirmation then run outside any business-row lock, and phase 2 (a new transaction that re-claims the
 * candidate and re-takes the lock) sets replica and candidate DELETED. DELETING is unreadable, cannot gain references,
 * and is resumed (never reverted) by later runs. ORPHAN_CANDIDATE and STAGING keep the single locked transaction.
 *
 * <h2>Stale multipart uploads</h2>
 * Aborting an abandoned multipart upload discards data, so it is a delete-class operation: it runs only in EXECUTE
 * mode, only with the STALE_MULTIPART class listed in execute-classes, under the same per-run cap, and each upload's
 * ownership (READY/LEASED transfer job, open upload intent) is re-checked immediately before its abort. In DRY_RUN or
 * with any gate closed the store is not even listed. Each abort is recorded as a STALE_MULTIPART inventory finding
 * with detail {@code ABORTED:<uploadId>}.
 *
 * <h2>Execute gates (all re-checked at runtime, per candidate)</h2>
 * execute-enabled, not dry-run, the object class listed in execute-classes, site mode CAMPUS (or explicitly
 * coordinated EXTERNAL revisions), a non-blank
 * execute-ack, a store that supports deletion, and the per-run delete cap. The default is a dry run.
 *
 * <h2>Why a row lock on a business row is held across the delete</h2>
 * See {@link #lockBusinessRow}. The V31 trigger {@code guard_reference_target_live} makes a re-reference wait for
 * the lock and then fail if the GC deleted the object, so a reference can never be attached to a deleted object.
 *
 * <p>Only the LOCAL store is ever passed to this class. All times use the database clock.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.storage.files.gc.enabled", havingValue = "true")
public class FileGcService {
    public static final String LOCAL = "LOCAL";
    public static final String STAGING = "STAGING";
    public static final String ORPHAN_CANDIDATE = "ORPHAN_CANDIDATE";
    public static final String UNREFERENCED_REVISION = "UNREFERENCED_REVISION";
    /** Execute class for aborting abandoned multipart uploads; a delete-class operation behind the same gates. */
    public static final String STALE_MULTIPART = "STALE_MULTIPART";

    // Candidate states.
    static final String DETECTED = "DETECTED";
    static final String ELIGIBLE = "ELIGIBLE";
    static final String BLOCKED = "BLOCKED";
    static final String DELETING = "DELETING";
    static final String DELETED = "DELETED";
    static final String KEPT = "KEPT";
    static final String REVIVED = "REVIVED";

    private static final int MAX_ERROR_TEXT = 2000;

    /** Bound on any row-lock wait inside a candidate transaction (the replica UPDATE is the only one that can wait). */
    private static final String LOCK_TIMEOUT_SQL = "SET LOCAL lock_timeout = '5s'";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Candidate predicate: claimable rows (never terminal ones). Shared by the listing and the claim. */
    private static final String CLAIMABLE = """
            s.site='LOCAL' AND (
              (s.state IN ('DETECTED','ELIGIBLE','BLOCKED','DELETING') AND (s.eligible_after IS NULL OR s.eligible_after<=clock_timestamp()))
              OR (s.state='LEASED' AND s.lease_until<=clock_timestamp()))
            """;

    public enum Mode { DRY_RUN, EXECUTE }

    /**
     * DELETE_PENDING: the first phase of a two-phase delete is committed (replica and candidate are DELETING) and the
     * physical delete plus the second phase are still to run (or are waiting for the execute gates / a lock).
     */
    public enum Kind { DELETED, ELIGIBLE, BLOCKED, REVIVED, KEPT, SKIPPED_LOCKED, DELETE_PENDING, STALE, NOT_CLAIMED }

    /** @param inserted rows inserted or re-opened per class by one detect() call */
    public record DetectResult(long orphanCandidates, long staging, long unreferencedRevisions) {
        public long total() {
            return orphanCandidates + staging + unreferencedRevisions;
        }
    }

    /** A held claim on one candidate. Every later write is fenced by {@code fencingToken}. */
    public record Lease(UUID candidateId, String candidateClass, String objectKey, UUID fileId, Integer revision,
                        long fencingToken) { }

    /** Result of processing one claimed candidate. */
    public record Outcome(Kind kind, String reason) { }

    /**
     * Result of one {@link #run()}.
     *
     * @param examined candidates claimed and evaluated
     * @param deleted objects deleted (always 0 in DRY_RUN)
     * @param eligible candidates that satisfied every rule (deleted ones are not counted here)
     * @param blockReasons BLOCKED/REVIVED/KEPT reasons with counts
     * @param multipartAborted stale multipart uploads aborted (always 0 unless EXECUTE with STALE_MULTIPART enabled)
     */
    public record RunResult(UUID runId, Mode mode, long examined, long deleted, long eligible, long blocked,
                            long revived, long kept, long skipped, long stale, long errors,
                            Map<String, Long> blockReasons, String error, long multipartAborted) { }

    private record Verdict(String state, String reason) {
        static Verdict eligible() { return new Verdict(ELIGIBLE, null); }
        static Verdict blocked(String reason) { return new Verdict(BLOCKED, reason); }
        static Verdict revived(String reason) { return new Verdict(REVIVED, reason); }
        static Verdict kept(String reason) { return new Verdict(KEPT, reason); }
    }

    private enum LockResult { LOCKED, CONTENDED, MISSING }

    /** Thrown to roll the candidate transaction back; nothing it did is kept. */
    private static final class GcAbort extends RuntimeException {
        GcAbort(String message) { super(message); }
        GcAbort(String message, Throwable cause) { super(message, cause); }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final FileGcProperties props;
    private final SiteProperties site;
    private final Supplier<? extends ReplicaObjectStore> localStore;
    private Supplier<FileGcProtocol> protocol=()->null;
    public void setGcProtocol(FileGcProtocol protocol) { this.protocol=()->protocol; }

    private final Object fallbackLock = new Object();
    private S3ReplicaObjectStore fallbackLocal;

    /**
     * Spring wiring. Only the LOCAL replica store is requested: the peer store is never injected, so this service
     * cannot delete from the peer site.
     */
    @Autowired
    public FileGcService(JdbcTemplate jdbc,
                         PlatformTransactionManager txManager,
                         FileGcProperties props,
                         SiteProperties site,
                         S3StorageProperties s3Properties,
                         @Qualifier("localReplicaStore") ObjectProvider<ReplicaObjectStore> localProvider,
                         ObjectProvider<FileGcProtocol> protocolProvider) {
        this.jdbc = Objects.requireNonNull(jdbc, "JdbcTemplate");
        this.tx = newTransactionTemplate(Objects.requireNonNull(txManager, "PlatformTransactionManager"));
        this.props = Objects.requireNonNull(props, "FileGcProperties");
        this.site = Objects.requireNonNull(site, "SiteProperties");
        this.protocol=protocolProvider::getIfAvailable;
        this.localStore = () -> {
            ReplicaObjectStore bean = localProvider.getIfAvailable();
            return bean != null ? bean : lazyLocalFallback(s3Properties);
        };
    }

    /** Direct wiring for tests and embedding. The supplier is the LOCAL store only; it may return null. */
    public FileGcService(JdbcTemplate jdbc,
                         PlatformTransactionManager txManager,
                         FileGcProperties props,
                         SiteProperties site,
                         Supplier<? extends ReplicaObjectStore> localStore) {
        this.jdbc = Objects.requireNonNull(jdbc, "JdbcTemplate");
        this.tx = newTransactionTemplate(Objects.requireNonNull(txManager, "PlatformTransactionManager"));
        this.props = Objects.requireNonNull(props, "FileGcProperties");
        this.site = Objects.requireNonNull(site, "SiteProperties");
        this.localStore = Objects.requireNonNull(localStore, "localStore");
    }

    private static TransactionTemplate newTransactionTemplate(PlatformTransactionManager txManager) {
        TransactionTemplate template = new TransactionTemplate(txManager);
        // Own connection and READ COMMITTED: "re-read every fact in a fresh statement" relies on each statement
        // seeing the latest committed data once the row lock is held.
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }

    /** Builds the local store from S3StorageProperties on first use, exactly as ReplicationStorageConfiguration does. */
    private ReplicaObjectStore lazyLocalFallback(S3StorageProperties s3) {
        synchronized (fallbackLock) {
            if (fallbackLocal == null) {
                log.info("File GC: building local store lazily from S3StorageProperties");
                fallbackLocal = new S3ReplicaObjectStore(s3.getEndpoint(), s3.getRegion(), s3.getBucket(),
                        s3.getAccessKey(), s3.getSecretKey(), s3.isPathStyle());
            }
            return fallbackLocal;
        }
    }

    @PreDestroy
    void closeFallback() {
        synchronized (fallbackLock) {
            if (fallbackLocal != null) {
                fallbackLocal.close();
                fallbackLocal = null;
            }
        }
    }

    // =====================================================================================================
    // Phase 1: detection
    // =====================================================================================================

    /**
     * Upsert candidate rows from database facts (site LOCAL). Pure database work: no object-store call, no
     * business-row mutation. DELETED and KEPT rows are never touched; REVIVED rows are re-opened as DETECTED when
     * the fact holds again.
     */
    public DetectResult detect() {
        long windowMs = props.getSafetyWindow().toMillis();
        long retentionMs = props.getRetention().toMillis();
        long orphans = jdbc.update("""
                INSERT INTO eatwhat_file.file_gc_candidate AS c(candidate_id, site, object_key, class, state)
                SELECT gen_random_uuid(), 'LOCAL', o.object_key, 'ORPHAN_CANDIDATE', 'DETECTED'
                FROM eatwhat_file.file_orphan_candidate o
                WHERE o.state IN ('WRITING','WRITTEN','ORPHANED')
                  AND o.updated_at < clock_timestamp() - (?::bigint * interval '1 millisecond')
                  AND NOT EXISTS (SELECT 1 FROM eatwhat_file.file_gc_candidate g
                                  WHERE g.site='LOCAL' AND g.object_key=o.object_key AND g.state<>'REVIVED')
                ON CONFLICT (site, object_key) DO UPDATE
                   SET state='DETECTED', block_reason=NULL, eligible_after=NULL, updated_at=clock_timestamp()
                   WHERE c.state='REVIVED' AND c.class='ORPHAN_CANDIDATE'
                """, windowMs);
        long staging = jdbc.update("""
                INSERT INTO eatwhat_file.file_gc_candidate AS c(candidate_id, site, object_key, class, state)
                SELECT gen_random_uuid(), 'LOCAL', i.staging_key, 'STAGING', 'DETECTED'
                FROM eatwhat_file.upload_intent i
                WHERE i.expires_at < clock_timestamp() - (?::bigint * interval '1 millisecond')
                  AND NOT EXISTS (SELECT 1 FROM eatwhat_file.file_gc_candidate g
                                  WHERE g.site='LOCAL' AND g.object_key=i.staging_key AND g.state<>'REVIVED')
                ON CONFLICT (site, object_key) DO UPDATE
                   SET state='DETECTED', block_reason=NULL, eligible_after=NULL, updated_at=clock_timestamp()
                   WHERE c.state='REVIVED' AND c.class='STAGING'
                """, windowMs);
        long unreferenced = jdbc.update("""
                INSERT INTO eatwhat_file.file_gc_candidate AS c(candidate_id, site, object_key, file_id, revision, class, state)
                SELECT gen_random_uuid(), 'LOCAL', r.final_key, t.file_id, t.revision, 'UNREFERENCED_REVISION', 'DETECTED'
                FROM eatwhat_file.file_tombstone t
                JOIN eatwhat_file.file_revision r ON r.file_id=t.file_id AND r.revision=t.revision
                WHERE t.unreferenced_since IS NOT NULL
                  AND t.unreferenced_since < clock_timestamp() - (?::bigint * interval '1 millisecond')
                  AND NOT EXISTS (SELECT 1 FROM eatwhat_file.file_gc_candidate g
                                  WHERE g.site='LOCAL' AND g.object_key=r.final_key AND g.state<>'REVIVED')
                ON CONFLICT (site, object_key) DO UPDATE
                   SET state='DETECTED', block_reason=NULL, eligible_after=NULL, updated_at=clock_timestamp()
                   WHERE c.state='REVIVED' AND c.class='UNREFERENCED_REVISION'
                """, retentionMs);
        DetectResult result = new DetectResult(orphans, staging, unreferenced);
        log.info("File GC detect: orphanCandidates={} staging={} unreferencedRevisions={}", orphans, staging, unreferenced);
        return result;
    }

    // =====================================================================================================
    // Phase 2: evaluate and act
    // =====================================================================================================

    /** The mode a run would use right now: EXECUTE only when every class-independent gate is open. */
    public Mode currentMode() {
        return executeGateRefusal() == null ? Mode.EXECUTE : Mode.DRY_RUN;
    }

    /** Detect, then evaluate and act. */
    public RunResult runCycle() {
        detect();
        return run();
    }

    /**
     * Evaluate and (when every gate is open) act on claimable candidates. In DRY_RUN nothing outside
     * file_gc_candidate/file_gc_run is written and the object store is never called.
     */
    public RunResult run() {
        Mode mode = currentMode();
        UUID runId = UUID.randomUUID();
        jdbc.update("INSERT INTO eatwhat_file.file_gc_run(run_id, site, mode, config_snapshot) VALUES (?, 'LOCAL', ?, ?::jsonb)",
                runId, mode.name(), snapshot(mode));

        List<UUID> ids = jdbc.queryForList("SELECT s.candidate_id FROM eatwhat_file.file_gc_candidate s WHERE "
                + CLAIMABLE + " ORDER BY (s.state='DELETING') DESC, s.updated_at, s.candidate_id LIMIT ?", UUID.class, props.getCandidateBatch());

        long examined = 0, deleted = 0, eligible = 0, blocked = 0, revived = 0, kept = 0, skipped = 0, stale = 0, errors = 0;
        Map<String, Long> reasons = new LinkedHashMap<>();
        String firstError = null;
        int cap = props.getMaxDeletesPerRun();
        for (UUID id : ids) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            if (mode == Mode.EXECUTE && deleted >= cap) {
                break; // per-run delete cap: the remaining candidates are left untouched for the next run
            }
            try {
                Outcome outcome = tx.execute(status -> {
                    Lease lease = claim(id);
                    if (lease == null) {
                        return new Outcome(Kind.NOT_CLAIMED, null);
                    }
                    return processInTransaction(lease, runId, mode);
                });
                if (outcome.kind() == Kind.DELETE_PENDING && outcome.reason() == null && mode == Mode.EXECUTE) {
                    // Phase 1 is committed. The object delete and phase 2 run outside any business-row lock.
                    outcome = completeDeletion(id, runId);
                }
                switch (outcome.kind()) {
                    case DELETED -> { examined++; deleted++; }
                    case ELIGIBLE -> { examined++; eligible++; }
                    case BLOCKED -> { examined++; blocked++; reasons.merge(outcome.reason(), 1L, Long::sum); }
                    case REVIVED -> { examined++; revived++; reasons.merge(outcome.reason(), 1L, Long::sum); }
                    case KEPT -> { examined++; kept++; reasons.merge(outcome.reason(), 1L, Long::sum); }
                    case SKIPPED_LOCKED, DELETE_PENDING -> { examined++; skipped++; }
                    case STALE -> stale++;
                    case NOT_CLAIMED -> { }
                }
            } catch (Exception failure) {
                if (isLockTimeout(failure)) {
                    // The whole claim+evaluate transaction rolled back, so the candidate is exactly as before.
                    examined++;
                    skipped++;
                    log.info("File GC run {} candidate {} skipped: lock wait timed out", runId, id);
                    continue;
                }
                errors++;
                examined++;
                String text = truncate(failure.getClass().getSimpleName() + ": " + failure.getMessage());
                if (firstError == null) {
                    firstError = text;
                }
                log.warn("File GC run {} candidate {} failed; transaction rolled back", runId, id, failure);
                recordFailure(id, runId, text);
            }
        }
        long multipartAborted = 0;
        if (mode == Mode.EXECUTE && !Thread.currentThread().isInterrupted()) {
            MultipartSweep sweep = sweepStaleMultipart(runId, Math.max(0, cap - (int) deleted));
            multipartAborted = sweep.aborted();
            errors += sweep.errors();
            if (firstError == null) {
                firstError = sweep.firstError();
            }
        }
        long findings = mode == Mode.EXECUTE ? deleted + multipartAborted : eligible;
        try {
            jdbc.update("""
                    UPDATE eatwhat_file.file_gc_run
                    SET finished_at=clock_timestamp(), listed_count=?, finding_count=?, error_count=?, error=?
                    WHERE run_id=?
                    """, examined, findings, errors, firstError, runId);
        } catch (RuntimeException failure) {
            log.warn("File GC run {} could not be finalised", runId, failure);
        }
        log.info("File GC run {} mode={} examined={} deleted={} multipartAborted={} eligible={} blocked={} revived={} kept={} skipped={} stale={} errors={}",
                runId, mode, examined, deleted, multipartAborted, eligible, blocked, revived, kept, skipped, stale, errors);
        return new RunResult(runId, mode, examined, deleted, eligible, blocked, revived, kept, skipped, stale, errors,
                reasons, firstError, multipartAborted);
    }

    /**
     * Claim one candidate: LEASED, fencing_token+1, lease_until on the database clock. The row is locked with
     * SKIP LOCKED so concurrent GC instances never process the same candidate. Returns null when the candidate is
     * not claimable (terminal state, backoff not elapsed, live lease held by someone else, locked, or not LOCAL).
     * When called inside a transaction the lock is held until that transaction ends.
     */
    public Lease claim(UUID candidateId) {
        long leaseMillis = props.getClaimLease().toMillis();
        return jdbc.query("""
                UPDATE eatwhat_file.file_gc_candidate c SET
                  state='LEASED',
                  fencing_token=c.fencing_token+1,
                  lease_until=clock_timestamp()+(?::bigint * interval '1 millisecond'),
                  updated_at=clock_timestamp()
                WHERE c.candidate_id=(
                  SELECT s.candidate_id FROM eatwhat_file.file_gc_candidate s
                  WHERE s.candidate_id=? AND\s""" + CLAIMABLE + """
                  FOR UPDATE SKIP LOCKED)
                RETURNING c.candidate_id, c.class, c.object_key, c.file_id, c.revision, c.fencing_token
                """, rs -> rs.next()
                        ? new Lease(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                                rs.getObject(4, UUID.class), (Integer) rs.getObject(5), rs.getLong(6))
                        : null,
                leaseMillis, candidateId);
    }

    /**
     * Evaluate and act on a claimed candidate in one transaction. A holder whose fencing token or lease is stale
     * changes nothing and deletes nothing (Kind.STALE).
     */
    public Outcome process(Lease lease, UUID runId, Mode mode) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(mode, "mode");
        try {
            Outcome outcome = tx.execute(status -> processInTransaction(lease, runId, mode));
            if (outcome.kind() == Kind.DELETE_PENDING && outcome.reason() == null && mode == Mode.EXECUTE) {
                return completeDeletion(lease.candidateId(), runId);
            }
            return outcome;
        } catch (RuntimeException failure) {
            if (!isLockTimeout(failure)) {
                throw failure;
            }
            // The transaction rolled back; give the (separately committed) claim back untouched.
            tx.executeWithoutResult(status -> release(lease, localReplicaIs(lease, "DELETING") ? DELETING : DETECTED));
            return new Outcome(Kind.SKIPPED_LOCKED, "LOCK_TIMEOUT");
        }
    }

    /** True for a PostgreSQL lock_not_available (55P03) failure, including lock_timeout. */
    private static boolean isLockTimeout(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof org.springframework.dao.CannotAcquireLockException) {
                return true;
            }
            if (t instanceof java.sql.SQLException sql && "55P03".equals(sql.getSQLState())) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private Outcome processInTransaction(Lease lease, UUID runId, Mode mode) {
        // 0. Fenced re-lock of the candidate row. A stale holder (token superseded, lease expired) stops here.
        boolean held = Boolean.TRUE.equals(jdbc.query("""
                SELECT 1 FROM eatwhat_file.file_gc_candidate
                WHERE candidate_id=? AND state='LEASED' AND fencing_token=? AND lease_until>clock_timestamp()
                FOR UPDATE SKIP LOCKED
                """, ResultSetRowExists.INSTANCE, lease.candidateId(), lease.fencingToken()));
        if (!held) {
            return new Outcome(Kind.STALE, null);
        }
        // Never wait indefinitely on a row lock held by someone else; a timeout rolls this candidate back untouched.
        jdbc.execute(LOCK_TIMEOUT_SQL);

        // 1. Key policy gates (before any business lookup).
        String keyProblem = keyRefusal(lease.objectKey(), lease.candidateClass());
        if (keyProblem != null) {
            finish(lease, BLOCKED, keyProblem, runId);
            return new Outcome(Kind.BLOCKED, keyProblem);
        }

        // 2. Lock the business row that guards the object, then (and only then) re-read every fact.
        boolean resuming = UNREFERENCED_REVISION.equals(lease.candidateClass()) && localReplicaIs(lease, "DELETING");
        LockResult lock = lockBusinessRow(lease);
        if (lock == LockResult.CONTENDED) {
            release(lease, resuming ? DELETING : DETECTED);
            return new Outcome(Kind.SKIPPED_LOCKED, "BUSINESS_ROW_LOCKED");
        }
        if (lock == LockResult.LOCKED && resuming) {
            return resume(lease, runId, mode);
        }
        if (lock == LockResult.MISSING) {
            finish(lease, BLOCKED, "BUSINESS_ROW_MISSING", runId);
            return new Outcome(Kind.BLOCKED, "BUSINESS_ROW_MISSING");
        }
        Verdict verdict = evaluate(lease);
        if (!ELIGIBLE.equals(verdict.state())) {
            finish(lease, verdict.state(), verdict.reason(), runId);
            return new Outcome(Kind.valueOf(verdict.state()), verdict.reason());
        }

        // 3. Eligible. Dry run, or any closed execute gate, only records ELIGIBLE.
        if (mode != Mode.EXECUTE || executeRefusal(lease.candidateClass()) != null) {
            finish(lease, ELIGIBLE, null, runId);
            return new Outcome(Kind.ELIGIBLE, null);
        }
        if (UNREFERENCED_REVISION.equals(lease.candidateClass())) {
            boolean replicated=you.v50to.eatwhat.cluster.ClusterGcBarrier.required(jdbc,lease.fileId(),lease.revision())||exists("SELECT 1 FROM eatwhat_file.file_replica WHERE file_id=? AND revision=? AND site='PEER'",lease.fileId(),lease.revision());
            if(site.getMode()==SiteProperties.Mode.EXTERNAL&&!replicated&&!you.v50to.eatwhat.cluster.ClusterGcBarrier.privateLocal(jdbc,lease.fileId(),lease.revision())) {
                finish(lease,BLOCKED,"NO_COORDINATED_DECISION",runId);return new Outcome(Kind.BLOCKED,"NO_COORDINATED_DECISION");
            }
            if(replicated&&(!props.isCoordinatedEnabled()||protocol.get()==null||!protocol.get().prepareOrCommit(lease.fileId(),lease.revision()))) {
                finish(lease,BLOCKED,"PEER_PREPARE_PENDING",runId);return new Outcome(Kind.BLOCKED,"PEER_PREPARE_PENDING");
            }
            beginDeletion(lease, runId);
            return new Outcome(Kind.DELETE_PENDING, null);
        }
        DeletableObjectStore store = (DeletableObjectStore) localStore.get();
        return execute(lease, store, runId);
    }

    // -----------------------------------------------------------------------------------------------------
    // Stale multipart uploads (delete-class: aborts discard uploaded parts)
    // -----------------------------------------------------------------------------------------------------

    private record MultipartSweep(long aborted, long errors, String firstError) {
        static final MultipartSweep NONE = new MultipartSweep(0, 0, null);
    }

    /**
     * Abort stale, unowned multipart uploads under the managed prefixes, at most {@code budget} of them. Does nothing
     * (and makes no store call at all) unless every execute gate is open for the STALE_MULTIPART class. The age rule
     * and the ownership rule are the ones the inventory reports with ({@link StaleMultipartPolicy}); ownership is
     * re-read immediately before each abort so an upload a job claimed after the listing is never touched.
     */
    private MultipartSweep sweepStaleMultipart(UUID runId, int budget) {
        if (budget <= 0 || executeRefusal(STALE_MULTIPART) != null) {
            return MultipartSweep.NONE;
        }
        long aborted = 0;
        long errors = 0;
        String firstError = null;
        try {
            DeletableObjectStore store = (DeletableObjectStore) localStore.get();
            java.time.Instant now = jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant();
            java.time.Instant cutoff = now.minus(StaleMultipartPolicy.minimumAge(props));
            for (String prefix : new String[]{props.getStagingPrefix(), props.getVerifiedPrefix()}) {
                if (!StringUtils.hasText(prefix)) {
                    continue;
                }
                for (InventoryObjectStore.MultipartUploadEntry upload : store.listMultipartUploads(prefix)) {
                    if (aborted >= budget || Thread.currentThread().isInterrupted()) {
                        return new MultipartSweep(aborted, errors, firstError);
                    }
                    try {
                        if (upload.initiated() == null || upload.initiated().isAfter(cutoff)
                                || !StaleMultipartPolicy.isManagedKey(props, upload.key())) {
                            continue;
                        }
                        // Re-check ownership and every gate right before the destructive call.
                        if (StaleMultipartPolicy.isOwned(jdbc, upload.key(), upload.uploadId())
                                || executeRefusal(STALE_MULTIPART) != null) {
                            continue;
                        }
                        store.abortMultipartUpload(upload.key(), upload.uploadId());
                        aborted++;
                        jdbc.update("""
                                INSERT INTO eatwhat_file.file_inventory_finding(run_id, site, object_key, kind, object_last_modified, detail)
                                VALUES (?, 'LOCAL', ?, 'STALE_MULTIPART', ?, ?)
                                """, runId, upload.key(), java.sql.Timestamp.from(upload.initiated()), "ABORTED:" + upload.uploadId());
                    } catch (RuntimeException failure) {
                        errors++;
                        if (firstError == null) {
                            firstError = truncate("multipart " + upload.key() + ": " + failure.getClass().getSimpleName()
                                    + ": " + failure.getMessage());
                        }
                        log.warn("File GC run {} could not sweep multipart upload {} of {}", runId, upload.uploadId(), upload.key(), failure);
                    }
                }
            }
        } catch (RuntimeException failure) {
            errors++;
            if (firstError == null) {
                firstError = truncate("multipart sweep: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }
            log.warn("File GC run {} stale-multipart sweep failed", runId, failure);
        }
        return new MultipartSweep(aborted, errors, firstError);
    }

    // -----------------------------------------------------------------------------------------------------
    // Execute gates
    // -----------------------------------------------------------------------------------------------------

    /** Class-independent gates. Returns the first closed gate, or null when all are open. */
    private String executeGateRefusal() {
        if (!props.isEnabled() || !props.isInventoryEnabled()) {
            return "GC_DISABLED";
        }
        if (!props.isExecuteEnabled()) {
            return "EXECUTE_DISABLED";
        }
        if (props.isDryRun()) {
            return "DRY_RUN";
        }
        if (site.getMode() != SiteProperties.Mode.CAMPUS
                && !(site.getMode()==SiteProperties.Mode.EXTERNAL&&props.isCoordinatedEnabled()&&protocol.get()!=null)) {
            return "SITE_NOT_CAMPUS";
        }
        if (!FileGcProperties.isAckUsable(props.getExecuteAck())) {
            return "ACK_MISSING"; // same rule as the startup validator: blank or an unresolved ${...} placeholder
        }
        if (props.getExecuteClasses() == null || props.getExecuteClasses().isEmpty()) {
            return "NO_EXECUTE_CLASSES";
        }
        // Only reached when every other gate is open, so resolving (and lazily building) the store is intended.
        if (!(localStore.get() instanceof DeletableObjectStore)) {
            return "STORE_NOT_DELETABLE";
        }
        return null;
    }

    /** All gates for one candidate class, re-checked right before the delete. Returns null when deletion is allowed. */
    private String executeRefusal(String candidateClass) {
        String gate = executeGateRefusal();
        if (gate != null) {
            return gate;
        }
        if(site.getMode()==SiteProperties.Mode.EXTERNAL&&!UNREFERENCED_REVISION.equals(candidateClass)) return "EXTERNAL_COORDINATED_REVISIONS_ONLY";
        if (candidateClass == null || !props.getExecuteClasses().contains(candidateClass)) {
            return "CLASS_NOT_ENABLED";
        }
        return null;
    }

    /** Managed-prefix and sanity gates. Returns a block reason, or null when the key may be considered. */
    private String keyRefusal(String key, String candidateClass) {
        if (key == null || key.isBlank()) {
            return "INVALID_KEY";
        }
        if (key.startsWith("/") || key.endsWith("/") || key.contains("..") || key.contains("\u0000") || key.contains("\\")) {
            return "INVALID_KEY";
        }
        String verified = props.getVerifiedPrefix();
        String staging = props.getStagingPrefix();
        if (!hasPrefix(key, verified) && !hasPrefix(key, staging)) {
            return "UNMANAGED_PREFIX";
        }
        // Each class may only delete from its own managed prefix.
        String required = STAGING.equals(candidateClass) ? staging : verified;
        if (!ORPHAN_CANDIDATE.equals(candidateClass) && !UNREFERENCED_REVISION.equals(candidateClass) && !STAGING.equals(candidateClass)) {
            return "UNKNOWN_CLASS";
        }
        if (!hasPrefix(key, required)) {
            return "UNMANAGED_PREFIX";
        }
        for (String legacy : props.getLegacyPrefixes()) {
            if (legacy != null && !legacy.isBlank() && key.startsWith(legacy)) {
                return "UNMANAGED_PREFIX";
            }
        }
        return null;
    }

    private static boolean hasPrefix(String key, String prefix) {
        return prefix != null && !prefix.isBlank() && key.length() > prefix.length() && key.startsWith(prefix);
    }

    // -----------------------------------------------------------------------------------------------------
    // Locking
    // -----------------------------------------------------------------------------------------------------

    /**
     * Lock the business row guarding the object. The lock is held until the transaction ends, i.e. across the delete.
     *
     * <p>Why this is sufficient (verified against FileLifecycleService):
     * <ul>
     *   <li>UNREFERENCED_REVISION: finalizeAndReference inserts into file_reference / file_reference_event. Their
     *       AFTER triggers update file_tombstone, and the V31 BEFORE INSERT trigger takes FOR UPDATE on the same
     *       tombstone row first. A concurrent re-reference therefore blocks on our lock; when it resumes it either
     *       sees our committed DELETED replica and fails, or (if we rolled back) proceeds against an intact object.
     *       A re-reference committed before we locked is visible to our re-read and yields REVIVED. SKIP LOCKED
     *       means we never wait: if someone holds the row we skip the candidate.</li>
     *   <li>ORPHAN_CANDIDATE: complete() takes its locks in the order upload_intent (claim, canonicalize step 1)
     *       -> ... -> file_orphan_candidate (last statement). We lock in the same order (intent, then orphan row).
     *       Its revision insert is not visible to us until it commits, so the orphan row lock alone is NOT
     *       enough: the last canonicalize statement must additionally require state='WRITTEN' and fail
     *       otherwise (changed in FileLifecycleService.canonicalize), which makes a canonicalize that raced us
     *       roll back instead of committing a revision whose object we deleted.</li>
     *   <li>STAGING: complete() claims the intent row (status/lease update); we lock the same row, and the rule
     *       requires the intent to be expired by more than the safety window with no live lease.</li>
     * </ul>
     */
    private LockResult lockBusinessRow(Lease lease) {
        switch (lease.candidateClass()) {
            case UNREFERENCED_REVISION -> {
                if (lease.fileId() == null || lease.revision() == null) {
                    return LockResult.MISSING;
                }
                boolean io=Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended('file-io:'||?::text||':'||?::text,0))",Boolean.class,lease.fileId().toString(),lease.revision().toString()));
                if(!io) return LockResult.CONTENDED;
                boolean revisionLock=Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(?::text||':'||?::text,0))",Boolean.class,lease.fileId().toString(),lease.revision().toString()));
                if(!revisionLock) return LockResult.CONTENDED;
                return lockOrClassify("""
                        SELECT 1 FROM eatwhat_file.file_tombstone WHERE file_id=? AND revision=? FOR UPDATE SKIP LOCKED
                        """, "SELECT EXISTS(SELECT 1 FROM eatwhat_file.file_tombstone WHERE file_id=? AND revision=?)",
                        lease.fileId(), lease.revision());
            }
            case ORPHAN_CANDIDATE -> {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT candidate_id, intent_id FROM eatwhat_file.file_orphan_candidate WHERE object_key=?", lease.objectKey());
                if (rows.isEmpty()) {
                    return LockResult.MISSING;
                }
                UUID orphanId = (UUID) rows.get(0).get("candidate_id");
                UUID intentId = (UUID) rows.get(0).get("intent_id");
                // Same order as complete(): the intent row first, then the orphan row.
                LockResult intent = lockOrClassify(
                        "SELECT 1 FROM eatwhat_file.upload_intent WHERE intent_id=? FOR NO KEY UPDATE SKIP LOCKED",
                        "SELECT EXISTS(SELECT 1 FROM eatwhat_file.upload_intent WHERE intent_id=?)", intentId);
                if (intent != LockResult.LOCKED) {
                    return intent;
                }
                return lockOrClassify(
                        "SELECT 1 FROM eatwhat_file.file_orphan_candidate WHERE candidate_id=? FOR UPDATE SKIP LOCKED",
                        "SELECT EXISTS(SELECT 1 FROM eatwhat_file.file_orphan_candidate WHERE candidate_id=?)", orphanId);
            }
            case STAGING -> {
                return lockOrClassify(
                        "SELECT 1 FROM eatwhat_file.upload_intent WHERE staging_key=? FOR NO KEY UPDATE SKIP LOCKED",
                        "SELECT EXISTS(SELECT 1 FROM eatwhat_file.upload_intent WHERE staging_key=?)", lease.objectKey());
            }
            default -> {
                return LockResult.MISSING;
            }
        }
    }

    private LockResult lockOrClassify(String lockSql, String existsSql, Object... args) {
        if (Boolean.TRUE.equals(jdbc.query(lockSql, ResultSetRowExists.INSTANCE, args))) {
            return LockResult.LOCKED;
        }
        // No row locked: either another transaction holds it (SKIP LOCKED) or it does not exist.
        boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(existsSql, Boolean.class, args));
        return exists ? LockResult.CONTENDED : LockResult.MISSING;
    }

    private enum ResultSetRowExists implements org.springframework.jdbc.core.ResultSetExtractor<Boolean> {
        INSTANCE;

        @Override
        public Boolean extractData(java.sql.ResultSet rs) throws java.sql.SQLException {
            return rs.next();
        }
    }

    // -----------------------------------------------------------------------------------------------------
    // Evaluation (all facts re-read after the lock; DB clock only)
    // -----------------------------------------------------------------------------------------------------

    private Verdict evaluate(Lease lease) {
        return switch (lease.candidateClass()) {
            case UNREFERENCED_REVISION -> evaluateUnreferenced(lease);
            case ORPHAN_CANDIDATE -> evaluateOrphan(lease);
            case STAGING -> evaluateStaging(lease);
            default -> Verdict.blocked("UNKNOWN_CLASS");
        };
    }

    private Verdict evaluateUnreferenced(Lease lease) {
        UUID fileId = lease.fileId();
        int revision = lease.revision();
        long retentionMs = props.getRetention().toMillis();

        List<Map<String, Object>> tomb = jdbc.queryForList("""
                SELECT t.unreferenced_since IS NOT NULL AS unreferenced, t.had_public_ref AS had_public_ref,
                       COALESCE(t.unreferenced_since < clock_timestamp() - (?::bigint * interval '1 millisecond'), false) AS aged
                FROM eatwhat_file.file_tombstone t WHERE t.file_id=? AND t.revision=?
                """, retentionMs, fileId, revision);
        if (tomb.isEmpty()) {
            return Verdict.blocked("NO_TOMBSTONE");
        }
        List<Map<String, Object>> rev = jdbc.queryForList(
                "SELECT final_key, state FROM eatwhat_file.file_revision WHERE file_id=? AND revision=?", fileId, revision);
        if (rev.isEmpty()) {
            return Verdict.blocked("NO_REVISION");
        }
        if (!lease.objectKey().equals(rev.get(0).get("final_key"))) {
            return Verdict.blocked("KEY_MISMATCH");
        }
        if (!"VERIFIED".equals(rev.get(0).get("state"))) {
            return Verdict.blocked("REVISION_NOT_VERIFIED");
        }
        int aliases = count("SELECT count(*) FROM eatwhat_file.file_key_alias WHERE file_id=? AND revision=?", fileId, revision);
        if (aliases == 0) {
            return Verdict.blocked("NO_ALIAS");
        }
        int live = count("""
                SELECT count(*) FROM eatwhat_file.file_reference_live f
                JOIN eatwhat_file.file_key_alias a ON a.logical_key=f.logical_key
                WHERE a.file_id=? AND a.revision=?
                """, fileId, revision);
        if (live > 0) {
            return Verdict.revived("LIVE_REFERENCE");
        }
        Map<String, Object> t = tomb.get(0);
        if (!Boolean.TRUE.equals(t.get("unreferenced"))) {
            return Verdict.blocked("TOMBSTONE_NOT_UNREFERENCED");
        }
        if (!Boolean.TRUE.equals(t.get("aged"))) {
            return Verdict.blocked("RETENTION_NOT_ELAPSED");
        }
        // Belt and braces: business rows can name a key without any file_reference (inbound sync, conflict resolution).
        if (businessRowReferences(fileId, revision)) {
            return Verdict.blocked("BUSINESS_REFERENCE_PRESENT");
        }

        List<Map<String, Object>> replicas = jdbc.queryForList(
                "SELECT site, state, object_key FROM eatwhat_file.file_replica WHERE file_id=? AND revision=?", fileId, revision);
        List<String> openJobs = jdbc.queryForList(
                "SELECT status FROM eatwhat_file.file_transfer_job WHERE file_id=? AND revision=? AND status<>'DONE'",
                String.class, fileId, revision);
        for (Map<String, Object> replica : replicas) {
            String state = (String) replica.get("state");
            if ("QUARANTINED".equals(state) || "PAUSED_CONSENT".equals(state)) {
                return Verdict.blocked("CONSENT_OR_QUARANTINE_HOLD");
            }
        }
        if (openJobs.contains("PAUSED_CONSENT") || openJobs.contains("QUARANTINED")) {
            return Verdict.blocked("CONSENT_OR_QUARANTINE_HOLD");
        }
        boolean clusterManaged=you.v50to.eatwhat.cluster.ClusterGcBarrier.required(jdbc,fileId,revision);
        boolean replicated=clusterManaged||replicas.stream().anyMatch(r->!LOCAL.equals(r.get("site")));
        boolean coordinated=replicated&&props.isCoordinatedEnabled()&&protocol.get()!=null;
        for (Map<String, Object> replica : replicas) {
            if (!LOCAL.equals(replica.get("site"))&&!coordinated) {
                // Replicated revisions require the separate durable prepare/commit protocol, never a snapshot ACK.
                return Verdict.blocked("PEER_ACK_UNAVAILABLE");
            }
        }
        if(coordinated&&!clusterManaged) {
            if(!exists("SELECT 1 FROM eatwhat_file.file_publication WHERE file_id=? AND revision=? AND peer_confirmed_at IS NOT NULL",fileId,revision)) return Verdict.blocked("PEER_METADATA_UNCONFIRMED");
            if(count("""
                SELECT count(*) FROM eatwhat_file.file_replica p JOIN eatwhat_file.file_revision r USING(file_id,revision)
                WHERE p.file_id=? AND p.revision=? AND p.object_key=r.final_key
                  AND p.verified_sha256=r.sha256 AND p.verified_size=r.size_bytes
                  AND ((p.site='LOCAL' AND p.state IN ('AVAILABLE_LOCAL','DELETED'))
                    OR (p.site='PEER' AND p.state IN ('PEER_VERIFIED','DELETED')))
                """,fileId,revision)!=2) return Verdict.blocked("REPLICA_PROOF_UNAVAILABLE");
        }
        if (openJobs.contains("READY") || openJobs.contains("LEASED")) {
            return Verdict.blocked("TRANSFER_PENDING");
        }
        if (!openJobs.isEmpty()) {
            return Verdict.blocked("TRANSFER_PENDING"); // any other non-DONE job state: unknown, so keep
        }
        List<String> publication = jdbc.queryForList(
                "SELECT global_state FROM eatwhat_file.file_publication WHERE file_id=? AND revision=?",
                String.class, fileId, revision);
        if (!coordinated && !you.v50to.eatwhat.cluster.ClusterGcBarrier.privateLocal(jdbc,fileId,revision) && !publication.isEmpty() && !"LOCAL_ONLY".equals(publication.get(0))) {
            return Verdict.blocked("PUBLICATION_NOT_LOCAL_ONLY");
        }
        String cdnBlock = cdnInvalidationBlock(fileId, revision, Boolean.TRUE.equals(t.get("had_public_ref")));
        if (cdnBlock != null) {
            return Verdict.blocked(cdnBlock);
        }
        boolean localOrigin = exists("""
                SELECT 1 FROM eatwhat_file.file_object fo
                JOIN eatwhat_file.upload_intent ui ON ui.logical_key=fo.logical_key AND ui.owner_gid=fo.owner_gid
                WHERE fo.file_id=? AND ui.status='COMPLETE'
                """, fileId);
        if (!coordinated && !localOrigin) {
            return Verdict.blocked("NOT_LOCAL_ORIGIN");
        }
        Map<String, Object> local = null;
        for (Map<String, Object> replica : replicas) {
            if (LOCAL.equals(replica.get("site"))) {
                local = replica;
            }
        }
        if (local == null) {
            return Verdict.blocked("LOCAL_REPLICA_MISSING");
        }
        if (!lease.objectKey().equals(local.get("object_key"))) {
            return Verdict.blocked("KEY_MISMATCH");
        }
        String localState = (String) local.get("state");
        if (!"AVAILABLE_LOCAL".equals(localState) && !DELETED.equals(localState)) {
            return Verdict.blocked("LOCAL_REPLICA_STATE");
        }
        if (exists("SELECT 1 FROM eatwhat_file.file_replica WHERE object_key=? AND NOT (file_id=? AND revision=?)",
                lease.objectKey(), fileId, revision)) {
            return Verdict.blocked("KEY_SHARED");
        }
        return Verdict.eligible();
    }

    private Verdict evaluateOrphan(Lease lease) {
        long windowMs = props.getSafetyWindow().toMillis();
        List<Map<String, Object>> orphan = jdbc.queryForList("""
                SELECT o.state, o.intent_id,
                       o.updated_at < clock_timestamp() - (?::bigint * interval '1 millisecond') AS aged
                FROM eatwhat_file.file_orphan_candidate o WHERE o.object_key=?
                """, windowMs, lease.objectKey());
        if (orphan.isEmpty()) {
            return Verdict.blocked("NO_ORPHAN_ROW");
        }
        String state = (String) orphan.get(0).get("state");
        if ("CANONICAL".equals(state)) {
            return Verdict.kept("ORPHAN_CANONICAL");
        }
        if (!"WRITING".equals(state) && !"WRITTEN".equals(state) && !"ORPHANED".equals(state)) {
            return Verdict.kept("ORPHAN_NOT_OPEN");
        }
        // Authoritative: a revision or replica that names this key makes it a live object, never an orphan.
        if (exists("SELECT 1 FROM eatwhat_file.file_revision WHERE final_key=?", lease.objectKey())
                || exists("SELECT 1 FROM eatwhat_file.file_replica WHERE object_key=?", lease.objectKey())) {
            return Verdict.kept("REVISION_EXISTS");
        }
        if (!Boolean.TRUE.equals(orphan.get(0).get("aged"))) {
            return Verdict.blocked("WINDOW_NOT_ELAPSED");
        }
        List<Map<String, Object>> intent = jdbc.queryForList("""
                SELECT status,
                       (status='CLAIMED' AND lease_until>clock_timestamp()) AS lease_live,
                       expires_at < clock_timestamp() - (?::bigint * interval '1 millisecond') AS expired
                FROM eatwhat_file.upload_intent WHERE intent_id=?
                """, windowMs, orphan.get(0).get("intent_id"));
        if (intent.isEmpty()) {
            return Verdict.blocked("INTENT_MISSING");
        }
        Map<String, Object> i = intent.get(0);
        if (Boolean.TRUE.equals(i.get("lease_live"))) {
            return Verdict.blocked("INTENT_LEASE_LIVE");
        }
        String status = (String) i.get("status");
        boolean terminal = "COMPLETE".equals(status) || "REJECTED".equals(status);
        if (!terminal && !Boolean.TRUE.equals(i.get("expired"))) {
            return Verdict.blocked("INTENT_ACTIVE");
        }
        return Verdict.eligible();
    }

    private Verdict evaluateStaging(Lease lease) {
        long windowMs = props.getSafetyWindow().toMillis();
        if (!hasPrefix(lease.objectKey(), props.getStagingPrefix())) {
            return Verdict.blocked("UNMANAGED_PREFIX");
        }
        List<Map<String, Object>> intent = jdbc.queryForList("""
                SELECT (status='CLAIMED' AND lease_until>clock_timestamp()) AS lease_live,
                       expires_at < clock_timestamp() - (?::bigint * interval '1 millisecond') AS expired
                FROM eatwhat_file.upload_intent WHERE staging_key=?
                """, windowMs, lease.objectKey());
        if (intent.isEmpty()) {
            return Verdict.blocked("NO_INTENT");
        }
        if (Boolean.TRUE.equals(intent.get(0).get("lease_live"))) {
            return Verdict.blocked("INTENT_LEASE_LIVE");
        }
        if (!Boolean.TRUE.equals(intent.get(0).get("expired"))) {
            return Verdict.blocked("WINDOW_NOT_ELAPSED");
        }
        // Never delete anything that is a canonical object (final key or any replica object key).
        if (exists("SELECT 1 FROM eatwhat_file.file_revision WHERE final_key=?", lease.objectKey())
                || exists("SELECT 1 FROM eatwhat_file.file_replica WHERE object_key=?", lease.objectKey())
                || exists("SELECT 1 FROM eatwhat_file.file_orphan_candidate WHERE object_key=?", lease.objectKey())) {
            return Verdict.blocked("KEY_IS_REVISION");
        }
        return Verdict.eligible();
    }

    // -----------------------------------------------------------------------------------------------------
    // Execute
    // -----------------------------------------------------------------------------------------------------

    /**
     * Apply an eligible ORPHAN_CANDIDATE or STAGING deletion in the single locked transaction: database changes first
     * (each fenced or row-count checked), the physical delete last. UNREFERENCED_REVISION uses the two-phase flow
     * ({@link #beginDeletion}, {@link #completeDeletion}) instead.
     */
    private Outcome execute(Lease lease, DeletableObjectStore store, UUID runId) {
        String key = lease.objectKey();
        switch (lease.candidateClass()) {
            case ORPHAN_CANDIDATE -> {
                boolean present = store.head(key).isPresent();
                String orphanState = jdbc.queryForObject(
                        "SELECT state FROM eatwhat_file.file_orphan_candidate WHERE object_key=?", String.class, key);
                if (!present && "WRITING".equals(orphanState)) {
                    // The final PUT may simply not have landed yet (object absent is not proof of an orphan while the
                    // row is still WRITING). Only WRITTEN/ORPHANED rows are marked CLEANED on an absent object.
                    finish(lease, BLOCKED, "WRITE_IN_FLIGHT", runId);
                    return new Outcome(Kind.BLOCKED, "WRITE_IN_FLIGHT");
                }
                int orphan = jdbc.update("""
                        UPDATE eatwhat_file.file_orphan_candidate SET state='CLEANED', updated_at=clock_timestamp()
                        WHERE object_key=? AND state IN ('WRITING','WRITTEN','ORPHANED')
                        """, key);
                if (orphan != 1) {
                    throw new GcAbort("orphan candidate row changed before cleanup");
                }
                finish(lease, DELETED, null, runId);
                if (present) {
                    physicalDelete(store, key);
                }
            }
            case STAGING -> {
                finish(lease, DELETED, null, runId);
                physicalDelete(store, key);
            }
            default -> throw new GcAbort("unknown candidate class");
        }
        return new Outcome(Kind.DELETED, null);
    }

    /**
     * The only place an object is deleted. The outcome of a failed delete is UNCERTAIN (the request may have been
     * applied): it is probed with HEAD. Absent means the deletion stands; present, or a failing probe, rolls the
     * transaction back (the replica stays AVAILABLE_LOCAL and the candidate is retried).
     *
     * <p>A thread interrupt must never silently produce "database says available, object gone". The interrupt flag
     * is therefore captured and cleared before any store call here (a set flag makes the probing HEAD fail
     * immediately) and restored on the way out, so the run loop still observes the interrupt.
     *
     * <p>After an acknowledged delete the object must be absent; if the confirming HEAD cannot answer, the
     * acknowledged delete stands (committing "deleted" is the consistent outcome).
     *
     * <p>For UNREFERENCED_REVISION this runs outside any business-row lock, between the two committed phases of the
     * two-phase delete (replica DELETING, then DELETED), so a crash or failed commit can never leave an
     * AVAILABLE_LOCAL replica pointing at a missing object: the replica is already DELETING (unreadable, and no new
     * reference can attach). ORPHAN_CANDIDATE and STAGING keep the single-transaction flow (nothing references them).
     */
    private void physicalDelete(DeletableObjectStore store, String key) {
        boolean interrupted = Thread.interrupted();
        try {
            try {
                store.deleteObject(key);
            } catch (RuntimeException deleteFailure) {
                if (Thread.interrupted() || isInterruptLike(deleteFailure)) {
                    interrupted = true;
                    log.warn("File GC: delete of {} was interrupted; outcome is uncertain, probing", key);
                }
                boolean absent = false;
                try {
                    absent = store.head(key).isEmpty();
                } catch (RuntimeException probeFailure) {
                    deleteFailure.addSuppressed(probeFailure);
                }
                if (!absent) {
                    throw new GcAbort("object delete failed", deleteFailure);
                }
                log.warn("File GC: delete of {} reported a failure but the object is absent; keeping the deletion", key, deleteFailure);
                return;
            }
            Optional<ReplicaObjectStore.ObjectMeta> after;
            try {
                after = store.head(key);
            } catch (RuntimeException confirmFailure) {
                log.warn("File GC: object {} was deleted but the confirming HEAD failed; keeping the deletion", key, confirmFailure);
                return;
            }
            if (after.isPresent()) {
                throw new GcAbort("object still present after delete");
            }
        } finally {
            if (interrupted || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** SDK abort / interrupt style failures: the request may or may not have been applied. */
    private static boolean isInterruptLike(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException
                    || t instanceof java.io.InterruptedIOException
                    || t instanceof software.amazon.awssdk.core.exception.AbortedException
                    || t instanceof software.amazon.awssdk.core.exception.ApiCallTimeoutException
                    || t instanceof software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------------------------------------
    // Two-phase delete of UNREFERENCED_REVISION
    // -----------------------------------------------------------------------------------------------------

    /**
     * Phase 1 (inside the claim transaction, after the locks and the full evaluation): the LOCAL replica becomes
     * DELETING and the candidate becomes DELETING (resumable, no lease). Both commit together. From this commit on the
     * revision is unreadable and unreferenceable (resolvers need AVAILABLE_LOCAL; the V31/V32 trigger rejects new
     * references and RESTOREs), and nothing can silently revert it.
     */
    private void beginDeletion(Lease lease, UUID runId) {
        int changed = jdbc.update("""
                UPDATE eatwhat_file.file_replica SET state='DELETING', updated_at=clock_timestamp()
                WHERE file_id=? AND revision=? AND site='LOCAL' AND object_key=? AND state='AVAILABLE_LOCAL'
                """, lease.fileId(), lease.revision(), lease.objectKey());
        if (changed != 1 && !localReplicaIs(lease, DELETED)) {
            throw new GcAbort("local replica row changed before the delete began");
        }
        finish(lease, DELETING, null, runId);
        // Atomically with the phase-1 commit: a revision that was ever public gets a DELETE invalidation (cached copies of
        // an object that no longer exists). It never blocks this candidate (the CDN gate runs before phase 1 only).
        jdbc.update("""
                INSERT INTO eatwhat_file.file_cdn_invalidation(task_id, file_id, revision, reason, generation)
                SELECT gen_random_uuid(), ?, ?, 'DELETE', x.next_generation
                FROM (SELECT COALESCE(MAX(generation), 0) + 1 AS next_generation FROM eatwhat_file.file_cdn_invalidation
                      WHERE file_id=? AND revision=? AND reason='DELETE') x
                WHERE EXISTS (SELECT 1 FROM eatwhat_file.file_tombstone WHERE file_id=? AND revision=? AND had_public_ref)
                  AND NOT EXISTS (SELECT 1 FROM eatwhat_file.file_cdn_invalidation
                                  WHERE file_id=? AND revision=? AND reason='DELETE' AND status IN ('READY','LEASED'))
                ON CONFLICT DO NOTHING
                """, lease.fileId(), lease.revision(), lease.fileId(), lease.revision(), lease.fileId(), lease.revision(),
                lease.fileId(), lease.revision());
    }

    /**
     * A candidate whose replica is already DELETING (a crash, a failed delete or a failed phase 2 left it so). The
     * rules that still make sense are re-checked; the replica state itself is never a reason to block, and a block
     * never reverts the replica: it raises a loud alert and leaves everything DELETING.
     */
    private Outcome resume(Lease lease, UUID runId, Mode mode) {
        String hold = null;
        if (you.v50to.eatwhat.cluster.ClusterGcBarrier.required(jdbc,lease.fileId(),lease.revision())||exists("SELECT 1 FROM eatwhat_file.file_replica WHERE file_id=? AND revision=? AND site<>'LOCAL'",
                lease.fileId(), lease.revision())) {
            if(!props.isCoordinatedEnabled()||protocol.get()==null||!protocol.get().prepareOrCommit(lease.fileId(),lease.revision()))
                hold = "PEER_ACK_UNAVAILABLE";
        }
        if (count("""
                SELECT count(*) FROM eatwhat_file.file_reference_live f
                JOIN eatwhat_file.file_key_alias a ON a.logical_key=f.logical_key
                WHERE a.file_id=? AND a.revision=?
                """, lease.fileId(), lease.revision()) > 0) {
            hold = "LIVE_REFERENCE";
        }
        if (hold != null) {
            log.error("File GC: revision {}#{} is DELETING but is now blocked ({}); manual review required",
                    lease.fileId(), lease.revision(), hold);
            alertOnce(lease, "GC_DELETING_BLOCKED",
                    "Revision " + lease.fileId() + "#" + lease.revision() + " is DELETING but the delete is blocked: " + hold);
            finish(lease, DELETING, hold, runId);
            return new Outcome(Kind.BLOCKED, hold);
        }
        finish(lease, DELETING, null, runId);
        if (mode != Mode.EXECUTE || executeRefusal(lease.candidateClass()) != null) {
            return new Outcome(Kind.DELETE_PENDING, "AWAITING_EXECUTE");
        }
        return new Outcome(Kind.DELETE_PENDING, null);
    }

    /**
     * Object delete (outside any transaction and lock), then phase 2 in a new transaction that re-claims the
     * candidate (fresh fencing token), re-acquires the business-row lock, re-checks that the replica is still DELETING,
     * and sets replica and candidate to DELETED. Every step is idempotent, so any failure leaves the candidate DELETING
     * and a later run simply repeats from here.
     */
    private Outcome completeDeletion(UUID candidateId, UUID runId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT object_key, state FROM eatwhat_file.file_gc_candidate WHERE candidate_id=? AND site='LOCAL'", candidateId);
        if (rows.isEmpty() || !DELETING.equals(rows.get(0).get("state"))) {
            return new Outcome(Kind.NOT_CLAIMED, null);
        }
        if (executeRefusal(UNREFERENCED_REVISION) != null) {
            return new Outcome(Kind.DELETE_PENDING, "AWAITING_EXECUTE");
        }
        String key = (String) rows.get(0).get("object_key");
        boolean prepared=Boolean.TRUE.equals(tx.execute(status->{
            var facts=jdbc.queryForMap("SELECT file_id,revision FROM eatwhat_file.file_gc_candidate WHERE candidate_id=?",candidateId);
            UUID file=(UUID)facts.get("file_id");int revision=((Number)facts.get("revision")).intValue();
            if(you.v50to.eatwhat.cluster.ClusterGcBarrier.privateLocal(jdbc,file,revision))return true;
            if(you.v50to.eatwhat.cluster.ClusterGcBarrier.managed(jdbc))return props.isCoordinatedEnabled()&&you.v50to.eatwhat.cluster.ClusterGcBarrier.committed(jdbc,file,revision);
            if(!exists("SELECT 1 FROM eatwhat_file.file_replica WHERE file_id=? AND revision=? AND site='PEER'",file,revision)) return site.getMode()==SiteProperties.Mode.CAMPUS;
            return props.isCoordinatedEnabled()&&protocol.get()!=null&&protocol.get().prepareOrCommit(file,revision);
        }));
        if(!prepared) return new Outcome(Kind.DELETE_PENDING,"COORDINATED_COMMIT_REQUIRED");
        physicalDelete((DeletableObjectStore) localStore.get(), key);

        return tx.execute(status -> {
            Lease lease = claim(candidateId);
            if (lease == null) {
                return new Outcome(Kind.SKIPPED_LOCKED, "CLAIM_UNAVAILABLE");
            }
            jdbc.execute(LOCK_TIMEOUT_SQL);
            LockResult lock = lockBusinessRow(lease);
            if (lock != LockResult.LOCKED) {
                release(lease, DELETING);
                return new Outcome(Kind.SKIPPED_LOCKED, "BUSINESS_ROW_LOCKED");
            }
            int changed = jdbc.update("""
                    UPDATE eatwhat_file.file_replica SET state='DELETED', updated_at=clock_timestamp()
                    WHERE file_id=? AND revision=? AND site='LOCAL' AND object_key=? AND state IN ('DELETING','DELETED')
                    """, lease.fileId(), lease.revision(), lease.objectKey());
            if (changed != 1) {
                throw new GcAbort("local replica is no longer DELETING; leaving the candidate DELETING for review");
            }
            jdbc.update("UPDATE eatwhat_file.file_gc_coordination SET state='DELETED',updated_at=clock_timestamp() WHERE file_id=? AND revision=? AND state='COMMITTED'",lease.fileId(),lease.revision());
            jdbc.update("UPDATE eatwhat_cluster.gc_fence SET state='DELETED' WHERE file_id=? AND revision=? AND state='COMMITTED'",lease.fileId(),lease.revision());
            if(you.v50to.eatwhat.cluster.ClusterGcBarrier.managed(jdbc))jdbc.update("UPDATE eatwhat_cluster.replica_fact SET state='DELETED' WHERE file_id=? AND revision=? AND site_id=(SELECT site_id FROM eatwhat_identity.allocation WHERE singleton) AND instance_id=(SELECT instance_id FROM eatwhat_identity.allocation WHERE singleton)",lease.fileId(),lease.revision());
            finish(lease, DELETED, null, runId);
            return new Outcome(Kind.DELETED, null);
        });
    }

    private boolean localReplicaIs(Lease lease, String state) {
        return exists("SELECT 1 FROM eatwhat_file.file_replica WHERE file_id=? AND revision=? AND site='LOCAL' AND state=?",
                lease.fileId(), lease.revision(), state);
    }

    /** True when any registered business column still holds one of the revision's alias keys. */
    private boolean businessRowReferences(UUID fileId, int revision) {
        List<String> keys = jdbc.queryForList(
                "SELECT logical_key FROM eatwhat_file.file_key_alias WHERE file_id=? AND revision=?", String.class, fileId, revision);
        if (keys.isEmpty()) {
            return false;
        }
        for (BusinessKeyColumns.Column column : BusinessKeyColumns.ALL) {
            boolean found = Boolean.TRUE.equals(jdbc.query(connection -> {
                var statement = connection.prepareStatement(column.existsSql());
                statement.setArray(1, connection.createArrayOf("text", keys.toArray(new String[0])));
                return statement;
            }, ResultSetRowExists.INSTANCE));
            if (found) {
                log.info("File GC: revision {}#{} is still named by {}.{}", fileId, revision, column.table(), column.column());
                return true;
            }
        }
        return Boolean.TRUE.equals(jdbc.query(connection -> {
            var statement=connection.prepareStatement(BusinessKeyColumns.PROFILE_AVATAR_SQL);
            var array=connection.createArrayOf("text",keys.toArray(new String[0]));
            statement.setArray(1,array);
            return statement;
        },ResultSetRowExists.INSTANCE));
    }

    /** One alert per (revision, type); the alert table is append-only and the next run must not repeat it. */
    private void alertOnce(Lease lease, String type, String message) {
        jdbc.update("""
                INSERT INTO eatwhat_file.file_replica_alert(alert_id, file_id, revision, site, alert_type, message, created_at)
                SELECT ?, ?, ?, 'LOCAL', ?, ?, clock_timestamp()
                WHERE NOT EXISTS (SELECT 1 FROM eatwhat_file.file_replica_alert WHERE file_id=? AND revision=? AND alert_type=?)
                """, UUID.randomUUID(), lease.fileId(), lease.revision(), type, message, lease.fileId(), lease.revision(), type);
    }

    /**
     * CDN gate for UNREFERENCED_REVISION. A revision that ever had a PUBLIC reference may only be deleted once the
     * latest-generation REVOKE invalidation task is DONE or SKIPPED, and no READY/LEASED task of any generation may
     * exist. Tasks are enqueued by the database when the last live reference is released.
     *
     * <p>DELIBERATE DEFAULT: the invalidation worker is off by default (cdn-invalidation.enabled=false), so such
     * tasks stay READY and this block stays in force; turning the worker on (NOOP provider marks them SKIPPED /
     * NO_PROVIDER, a real provider marks them DONE) is what releases it.
     */
    private String cdnInvalidationBlock(UUID fileId, int revision, boolean hadPublicRef) {
        if (exists("SELECT 1 FROM eatwhat_file.file_cdn_invalidation WHERE file_id=? AND revision=? AND status IN ('READY','LEASED')",
                fileId, revision)) {
            return "CDN_INVALIDATION_PENDING";
        }
        if (!hadPublicRef) {
            return null;
        }
        List<String> latest = jdbc.queryForList("""
                SELECT status FROM eatwhat_file.file_cdn_invalidation
                WHERE file_id=? AND revision=? AND reason='REVOKE' ORDER BY generation DESC LIMIT 1
                """, String.class, fileId, revision);
        if (latest.isEmpty()) {
            return "CDN_INVALIDATION_UNAVAILABLE";
        }
        if ("SKIPPED".equals(latest.get(0)) && exists("""
                SELECT 1 FROM eatwhat_file.file_cdn_invalidation
                WHERE file_id=? AND revision=? AND reason='REVOKE' AND status='SKIPPED' AND skip_reason='REVIVED'
                  AND generation=(SELECT MAX(generation) FROM eatwhat_file.file_cdn_invalidation
                                  WHERE file_id=? AND revision=? AND reason='REVOKE')
                """, fileId, revision, fileId, revision)) {
            // The latest task was skipped because the revision was publicly referenced again. It is unreferenced now, so
            // that revoke is moot but the NEW exposure has no task yet: wait until a newer-generation task exists and is done.
            return "CDN_INVALIDATION_PENDING";
        }
        return switch (latest.get(0)) {
            case "DONE", "SKIPPED" -> null;
            case "QUARANTINED" -> "CDN_INVALIDATION_FAILED";
            default -> "CDN_INVALIDATION_PENDING";
        };
    }

    // -----------------------------------------------------------------------------------------------------
    // Fenced candidate writes
    // -----------------------------------------------------------------------------------------------------

    /** Move a leased candidate to a resting state. Changes nothing (and throws) for a stale holder. */
    private void finish(Lease lease, String state, String reason, UUID runId) {
        boolean backoff = BLOCKED.equals(state) || (DELETING.equals(state) && reason != null);
        int changed = jdbc.update("""
                UPDATE eatwhat_file.file_gc_candidate SET
                  state=?, block_reason=?,
                  eligible_after=CASE WHEN ?::boolean THEN clock_timestamp()+(?::bigint * interval '1 millisecond') ELSE NULL END,
                  lease_until=NULL,
                  run_id=COALESCE(?::uuid, run_id),
                  deleted_at=CASE WHEN ?::text='DELETED' THEN clock_timestamp() ELSE deleted_at END,
                  updated_at=clock_timestamp()
                WHERE candidate_id=? AND state='LEASED' AND fencing_token=? AND lease_until>clock_timestamp()
                """, state, reason, backoff, props.getGcInterval().toMillis(), runId, state,
                lease.candidateId(), lease.fencingToken());
        if (changed != 1) {
            throw new GcAbort("stale lease: candidate " + lease.candidateId() + " is no longer held by this claim");
        }
    }

    /** Give the claim back untouched (lock contention): back to DETECTED, no backoff, no attempt counted. */
    private void release(Lease lease, String restoreState) {
        jdbc.update("""
                UPDATE eatwhat_file.file_gc_candidate SET state=?, lease_until=NULL, eligible_after=NULL,
                  updated_at=clock_timestamp()
                WHERE candidate_id=? AND state='LEASED' AND fencing_token=? AND lease_until>clock_timestamp()
                """, restoreState, lease.candidateId(), lease.fencingToken());
    }

    /** After a rollback: attempts+1, last_error, back to DETECTED with an exponential backoff on eligible_after. */
    private void recordFailure(UUID candidateId, UUID runId, String error) {
        try {
            tx.executeWithoutResult(status -> jdbc.update("""
                    UPDATE eatwhat_file.file_gc_candidate SET
                      attempts=attempts+1, last_error=?, state=CASE WHEN state='DELETING' THEN 'DELETING' ELSE 'DETECTED' END, lease_until=NULL,
                      eligible_after=clock_timestamp()+(LEAST(86400000::bigint, ?::bigint * (1::bigint << LEAST(attempts, 6))) * interval '1 millisecond'),
                      run_id=?, updated_at=clock_timestamp()
                    WHERE candidate_id=? AND (state IN ('DETECTED','ELIGIBLE','BLOCKED','REVIVED','DELETING')
                                              OR (state='LEASED' AND lease_until<=clock_timestamp()))
                    """, error, props.getGcInterval().toMillis(), runId, candidateId));
            // A DELETING candidate that keeps failing is an operational problem: one alert per revision.
            tx.executeWithoutResult(status -> jdbc.update("""
                    INSERT INTO eatwhat_file.file_replica_alert(alert_id, file_id, revision, site, alert_type, message, created_at)
                    SELECT gen_random_uuid(), c.file_id, c.revision, 'LOCAL', 'GC_DELETE_STUCK',
                           'Physical delete of revision ' || c.file_id || '#' || c.revision || ' is stuck in DELETING after '
                             || c.attempts || ' attempts', clock_timestamp()
                    FROM eatwhat_file.file_gc_candidate c
                    WHERE c.candidate_id=? AND c.class='UNREFERENCED_REVISION' AND c.state='DELETING' AND c.attempts>=?
                      AND NOT EXISTS (SELECT 1 FROM eatwhat_file.file_replica_alert a
                                      WHERE a.file_id=c.file_id AND a.revision=c.revision AND a.alert_type='GC_DELETE_STUCK')
                    """, candidateId, props.getStuckAlertAfterAttempts()));
        } catch (RuntimeException failure) {
            log.warn("File GC could not record the failure of candidate {}", candidateId, failure);
        }
    }

    // -----------------------------------------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------------------------------------

    private boolean exists(String selectSql, Object... args) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(" + selectSql + ")", Boolean.class, args));
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    /** Run configuration for the audit row. The execute-ack value is never recorded, only whether one is present. */
    private String snapshot(Mode mode) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("mode", mode.name());
        config.put("retentionSeconds", props.getRetention().toSeconds());
        config.put("safetyWindowSeconds", props.getSafetyWindow().toSeconds());
        config.put("executeClasses", new ArrayList<>(new TreeSet<>(props.getExecuteClasses())));
        config.put("maxDeletesPerRun", props.getMaxDeletesPerRun());
        config.put("dryRun", props.isDryRun());
        config.put("executeEnabled", props.isExecuteEnabled());
        config.put("coordinatedEnabled", props.isCoordinatedEnabled());
        config.put("multipartTtlSeconds", props.getMultipartTtl().toSeconds());
        config.put("claimLeaseSeconds", props.getClaimLease().toSeconds());
        config.put("gcIntervalSeconds", props.getGcInterval().toSeconds());
        config.put("siteMode", String.valueOf(site.getMode()));
        config.put("ackPresent", FileGcProperties.isAckUsable(props.getExecuteAck()));
        try {
            return JSON.writeValueAsString(config);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            return "{}";
        }
    }

    private static String truncate(String text) {
        return text == null || text.length() <= MAX_ERROR_TEXT ? text : text.substring(0, MAX_ERROR_TEXT);
    }
}
