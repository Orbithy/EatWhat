package you.v50to.eatwhat.service.storage.replication;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import you.v50to.eatwhat.config.FileReplicationProperties;
import you.v50to.eatwhat.service.storage.replication.ReplicaObjectStore.UploadListener;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.function.ToLongFunction;

/**
 * Processes file replication jobs: downloads from source store, verifies integrity,
 * uploads to destination store, with consent gating and transient retry logic.
 *
 * Processing steps:
 * 1. Consent gate: check if owner has granted replication consent
 * 2. Identify source/destination stores based on job kind (COPY_TO_PEER or PULL_FROM_PEER)
 * 3. Destination already has object? Verify via readback; if matching, complete without put
 * 4. Download from source to temp file with SHA-256 verification
 * 5. Extend lease before upload (fail if lost)
 * 6. Upload to destination (large objects use multipart: the upload id is persisted on the job, the lease is
 *    extended while parts are uploaded, and the upload is aborted by the store on any failure); post-verify if
 *    FULL_READBACK mode
 * 7. Re-check consent before completing
 *
 * Before downloading, free space in the temp directory must cover the object plus a reserve (else DISK_SPACE, retried).
 * Transient failures (IO, network, SDK errors, concurrent putFile) retry with exponential backoff.
 * Non-recoverable failures (SOURCE_NOT_FOUND, hash/size mismatch, lost lease) are quarantined or paused.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.storage.files.replication.enabled", havingValue = "true")
@ConditionalOnProperty(name = "app.storage.files.replication.worker-enabled", havingValue = "true")
public class FileReplicationWorker {

    private final FileTransferJobStore jobs;
    private final ReplicationConsentGate consentGate;
    private final ReplicaObjectStore localStore;
    private final ReplicaObjectStore peerStore;
    private final FileReplicationProperties props;
    private final Random rng;
    private final ToLongFunction<Path> usableSpace;

    /** Lease extension while a multipart upload runs: every this many parts, or by the time interval below. */
    private static final int HEARTBEAT_EVERY_PARTS = 8;
    private static final long HEARTBEAT_MAX_NANOS = Duration.ofSeconds(30).toNanos();
    private static final long HEARTBEAT_MIN_NANOS = Duration.ofSeconds(1).toNanos();

    /**
     * Public constructor for production use, with Spring-wired stores and consent gate.
     */
    @Autowired
    public FileReplicationWorker(FileTransferJobStore jobs,
                                ReplicationConsentGate consentGate,
                                @Qualifier("localReplicaStore") ReplicaObjectStore localStore,
                                @Qualifier("peerReplicaStore") ReplicaObjectStore peerStore,
                                FileReplicationProperties props) {
        this(jobs, consentGate, localStore, peerStore, props, new Random());
    }

    /**
     * Package-private constructor for testing, allowing custom RandomGenerator.
     */
    public FileReplicationWorker(FileTransferJobStore jobs,
                                ReplicationConsentGate consentGate,
                                ReplicaObjectStore localStore,
                                ReplicaObjectStore peerStore,
                                FileReplicationProperties props,
                                Random rng) {
        this(jobs, consentGate, localStore, peerStore, props, rng, path -> path.toFile().getUsableSpace());
    }

    /**
     * Constructor for tests that need to inject the free-space probe of the temp directory.
     */
    public FileReplicationWorker(FileTransferJobStore jobs,
                                ReplicationConsentGate consentGate,
                                ReplicaObjectStore localStore,
                                ReplicaObjectStore peerStore,
                                FileReplicationProperties props,
                                Random rng,
                                ToLongFunction<Path> usableSpace) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.consentGate = Objects.requireNonNull(consentGate, "consentGate");
        this.localStore = Objects.requireNonNull(localStore, "localStore");
        this.peerStore = Objects.requireNonNull(peerStore, "peerStore");
        this.props = Objects.requireNonNull(props, "props");
        this.rng = Objects.requireNonNull(rng, "rng");
        this.usableSpace = Objects.requireNonNull(usableSpace, "usableSpace");
    }

    /**
     * Outcome of processing a single job.
     */
    public enum Outcome {
        COMPLETED,      // Job finished successfully
        RETRY,          // Transient error; job will be retried
        QUARANTINED,    // Non-recoverable error; job stopped permanently
        PAUSED,         // Consent withdrawn; job paused
        LOST_LEASE      // Lease expired during processing; changes not persisted
    }

    /**
     * Process a single already-claimed job through the 7-step replication pipeline.
     * Exceptions are caught and converted to RETRY, QUARANTINED, PAUSED, or LOST_LEASE outcomes.
     * The temp file is always deleted in a finally block.
     *
     * @param job the claimed job to process
     * @return outcome of processing
     */
    public Outcome process(FileTransferJobStore.Job job) {
        Objects.requireNonNull(job, "job");
        try(var fence=jobs.acquireIoFence(job)) {
            if(jobs.isDeletionFrozen(job)) return Outcome.LOST_LEASE;
            return processWithFence(job);
        } catch(RuntimeException failure) {
            log.warn("File transfer I/O fence unavailable for job {} ({})",job.jobId(),failure.getClass().getSimpleName());
            return Outcome.LOST_LEASE;
        }
    }

    private Outcome processWithFence(FileTransferJobStore.Job job) {
        Path tmpFile = null;
        try {
            // 1. Consent gate check
            try {
                if (!consentGate.replicationAllowed(job.ownerGid())) {
                    log.warn("Consent revoked for job {}; pausing", job.jobId());
                    if (jobs.pauseConsent(job)) {
                        return Outcome.PAUSED;
                    } else {
                        return Outcome.LOST_LEASE;
                    }
                }
            } catch (IllegalArgumentException e) {
                if (e.getMessage().contains("IDENTITY_PENDING")) {
                    log.debug("Identity not yet synced for job {}; retrying", job.jobId());
                    Duration delay = FileTransferJobStore.backoff(job.attempts(), props.getRetryBase(),
                            props.getRetryMax(), props.getJitter(), rng);
                    return retry(job, delay, "IDENTITY_PENDING", e.getMessage());
                }
                throw e;
            }

            // 1b. The GC may have physically deleted the local source: nothing to copy, and retrying cannot help.
            boolean sourceDeleted="COPY_TO_PEER".equals(job.kind())?jobs.isLocalSourceDeleted(job):jobs.isPeerSourceDeleted(job);
            if (sourceDeleted) {
                log.warn("Source of job {} was physically deleted; quarantining", job.jobId());
                return quarantine(job, "SOURCE_DELETED", "Source replica was physically deleted by the file GC");
            }
            if(jobs.isTargetReplicaDeleted(job)) {
                return quarantine(job,"TARGET_DELETED","Target replica was physically deleted by the file GC");
            }

            // 2. Determine source and destination stores based on job kind
            ReplicaObjectStore srcStore = "COPY_TO_PEER".equals(job.kind()) ? localStore : peerStore;
            ReplicaObjectStore dstStore = "COPY_TO_PEER".equals(job.kind()) ? peerStore : localStore;

            // 3. Check if destination already has the object (lost-ACK case)
            var dstMeta = dstStore.head(job.finalKey());
            if (dstMeta.isPresent()) {
                log.debug("Destination already has object {}; verifying via readback", job.finalKey());
                try {
                    var dstDigest = dstStore.readbackDigest(job.finalKey(), job.sizeBytes());
                    if (digestMatches(dstDigest, job.sha256(), job.sizeBytes())) {
                        // Object is correct; complete without re-uploading
                        log.info("Object already verified at destination; completing job {}", job.jobId());
                        var verified = new ReplicaObjectStore.DigestResult(dstDigest.size(), dstDigest.sha256Hex());
                        return completeOrExplain(job, verified);
                    } else {
                        // Destination has wrong object; quarantine and never overwrite
                        log.error("Target object mismatch for key {} (expected {}, got {}); quarantining",
                                job.finalKey(), job.sha256(), dstDigest.sha256Hex());
                        return quarantine(job, "TARGET_MISMATCH",
                                "Destination has different content: " + dstDigest.sha256Hex());
                    }
                } catch (ObjectTooLargeException e) {
                    log.error("Destination object too large for key {}", job.finalKey());
                    return quarantine(job, "TARGET_MISMATCH", "Destination object exceeds size limit");
                }
            }

            // 3b. Disk precheck: the whole object is staged in the temp directory. Too little space is transient
            // (space frees up or an operator intervenes); it must never quarantine the job.
            Path tempDir = tempDirPath();
            long needed = saturatedAdd(job.sizeBytes(), props.getTempReserveBytes());
            long usable = usableSpace.applyAsLong(tempDir);
            if (usable < needed) {
                log.warn("Not enough free space in the temp directory for job {} (need {} bytes, usable {}); retrying",
                        job.jobId(), needed, usable);
                Duration delay = FileTransferJobStore.backoff(job.attempts(), props.getRetryBase(),
                        props.getRetryMax(), props.getJitter(), rng);
                return retry(job, delay, "DISK_SPACE",
                        "Temp directory has " + usable + " usable bytes, needs " + needed);
            }

            // 4. Download from source to temp file with SHA-256 verification
            tmpFile = createSecureTempFile();

            ReplicaObjectStore.DigestResult srcDigest;
            try {
                srcDigest = srcStore.downloadTo(job.finalKey(), tmpFile, job.sizeBytes());
            } catch (ObjectNotFoundException e) {
                // Source object missing (metadata uploaded before bytes); retry without quarantine
                log.warn("Source object not found for key {} (job: {}); retrying", job.finalKey(), job.jobId());
                Duration delay = FileTransferJobStore.backoff(job.attempts(), props.getRetryBase(),
                        props.getRetryMax(), props.getJitter(), rng);
                return retry(job, delay, "SOURCE_NOT_FOUND",
                        "Object not yet available on source: " + job.finalKey());
            } catch (ObjectTooLargeException e) {
                log.error("Source object exceeds size limit for key {}", job.finalKey());
                return quarantine(job, "SOURCE_MISMATCH", "Source object too large: " + e.getMessage());
            }

            // Verify source digest matches revision
            if (!digestMatches(srcDigest, job.sha256(), job.sizeBytes())) {
                log.error("Source digest mismatch for key {} (expected {}, got {}); quarantining",
                        job.finalKey(), job.sha256(), srcDigest.sha256Hex());
                return quarantine(job, "SOURCE_MISMATCH",
                        "Source bytes don't match revision SHA-256: " + srcDigest.sha256Hex());
            }

            // 5. Extend lease before upload (fail if lost)
            if (!jobs.extendLease(job, Duration.ofSeconds(props.getLeaseSeconds()))) {
                log.warn("Lost lease on job {} during download; bailing out", job.jobId());
                return Outcome.LOST_LEASE;
            }

            // 6. Upload to destination. A multipart upload reports its id and progress to the tracker, which persists the
            // id on the job (fenced), keeps the lease alive and aborts the upload by throwing LeaseLostException.
            MultipartTracker tracker = new MultipartTracker(job);
            try {
                dstStore.putFile(job.finalKey(), job.contentType(), tmpFile, job.sha256(), tracker);
            } catch (IllegalStateException e) {
                // Object appeared concurrently at destination during our put attempt
                log.warn("Object appeared at destination concurrently (job {}); treating as transient", job.jobId());
                Duration delay = FileTransferJobStore.backoff(job.attempts(), props.getRetryBase(),
                        props.getRetryMax(), props.getJitter(), rng);
                return retry(job, delay, "TRANSIENT", "Concurrent write detected; will verify on retry");
            } finally {
                tracker.finish();
            }

            // A large readback can outlast the remaining lease; keep it alive after a multipart upload.
            if (tracker.started && !jobs.extendLease(job, Duration.ofSeconds(props.getLeaseSeconds()))) {
                log.warn("Lost lease on job {} after the multipart upload; bailing out", job.jobId());
                return Outcome.LOST_LEASE;
            }

            // Post-upload verification if FULL_READBACK mode
            if (props.getVerifyMode() == FileReplicationProperties.VerifyMode.FULL_READBACK) {
                ReplicaObjectStore.DigestResult uploadedDigest;
                try {
                    uploadedDigest = dstStore.readbackDigest(job.finalKey(), job.sizeBytes());
                } catch (ObjectNotFoundException e) {
                    // Object disappeared after put (very rare); retry
                    log.warn("Uploaded object disappeared during readback (job {}); retrying", job.jobId());
                    Duration delay = FileTransferJobStore.backoff(job.attempts(), props.getRetryBase(),
                            props.getRetryMax(), props.getJitter());
                    return retry(job, delay, "TRANSIENT", "Post-upload verification failed (object vanished)");
                } catch (ObjectTooLargeException e) {
                    log.error("Uploaded object exceeds size limit during readback for key {}", job.finalKey());
                    return quarantine(job, "READBACK_MISMATCH", "Uploaded object too large");
                }

                if (!digestMatches(uploadedDigest, job.sha256(), job.sizeBytes())) {
                    log.error("Post-upload readback mismatch for key {} (expected {}, got {})",
                            job.finalKey(), job.sha256(), uploadedDigest.sha256Hex());
                    return quarantine(job, "READBACK_MISMATCH",
                            "Uploaded object corrupt: " + uploadedDigest.sha256Hex());
                }
            }

            // 7. Re-check consent before completing
            try {
                if (!consentGate.replicationAllowed(job.ownerGid())) {
                    log.warn("Consent revoked for job {} after upload; pausing", job.jobId());
                    if (jobs.pauseConsent(job)) {
                        return Outcome.PAUSED;
                    } else {
                        return Outcome.LOST_LEASE;
                    }
                }

                Long currentEpoch = consentGate.currentEpoch(job.ownerGid());
                if (job.consentEpoch() != null && !job.consentEpoch().equals(currentEpoch)) {
                    log.warn("Consent epoch changed for job {} (was {}, now {}); pausing",
                            job.jobId(), job.consentEpoch(), currentEpoch);
                    if (jobs.pauseConsent(job)) {
                        return Outcome.PAUSED;
                    } else {
                        return Outcome.LOST_LEASE;
                    }
                }
            } catch (IllegalArgumentException e) {
                if (e.getMessage().contains("IDENTITY_PENDING")) {
                    log.warn("Identity lost after upload for job {}; pausing to be safe", job.jobId());
                    if (jobs.pauseConsent(job)) {
                        return Outcome.PAUSED;
                    } else {
                        return Outcome.LOST_LEASE;
                    }
                }
                throw e;
            }

            // Complete the job
            var uploadedDigest = new ReplicaObjectStore.DigestResult(srcDigest.size(), srcDigest.sha256Hex());
            return completeOrExplain(job, uploadedDigest);

        } catch (LeaseLostException e) {
            // The job belongs to a new holder; the store already aborted its upload. Change nothing else.
            log.warn("Lost lease on job {} during the upload: {}", job.jobId(), e.getMessage());
            return Outcome.LOST_LEASE;
        } catch (IOException | UncheckedIOException e) {
            log.warn("IO error processing job {}: {}", job.jobId(), e.getMessage());
            Duration delay = FileTransferJobStore.backoff(job.attempts(), props.getRetryBase(),
                    props.getRetryMax(), props.getJitter());
            return retry(job, delay, "TRANSIENT", truncate(e.getMessage(), 512));
        } catch (software.amazon.awssdk.core.exception.SdkException e) {
            log.warn("S3/SDK error processing job {}: {}", job.jobId(), e.getMessage());
            Duration delay = FileTransferJobStore.backoff(job.attempts(), props.getRetryBase(),
                    props.getRetryMax(), props.getJitter());
            return retry(job, delay, "TRANSIENT", truncate(e.getMessage(), 512));
        } catch (RuntimeException e) {
            log.error("Unexpected error processing job {}: {}", job.jobId(), e.getMessage(), e);
            Duration delay = FileTransferJobStore.backoff(job.attempts(), props.getRetryBase(),
                    props.getRetryMax(), props.getJitter());
            return retry(job, delay, "TRANSIENT", truncate(e.getMessage(), 512));
        } finally {
            // Always clean up temp file
            if (tmpFile != null) {
                try {
                    Files.deleteIfExists(tmpFile);
                } catch (IOException e) {
                    log.warn("Failed to delete temp file {}: {}", tmpFile, e.getMessage());
                }
            }
        }
    }

    /**
     * Claim and process a batch of jobs.
     * Exceptions per job are caught so one failure doesn't stop the batch.
     * Lease-stale exceptions are handled gracefully.
     *
     * @return number of jobs processed (completed, retried, quarantined, or paused)
     */
    public int runOnce() {
        List<FileTransferJobStore.Job> batch = jobs.claim(props.getBatch(),
                Duration.ofSeconds(props.getLeaseSeconds()));

        int processed = 0;
        for (FileTransferJobStore.Job job : batch) {
            try {
                Outcome outcome = process(job);
                if (outcome != null) {
                    processed++;
                    log.debug("Job {} processed with outcome: {}", job.jobId(), outcome);
                }
            } catch (Exception e) {
                // Never let a per-job exception escape; log and continue
                log.error("Fatal error processing job {} (will attempt cleanup): {}", job.jobId(), e.getMessage(), e);
                processed++;
            }
        }

        if (processed > 0) {
            log.info("Processed {} job(s) in batch", processed);
        }
        return processed;
    }

    /**
     * Helper: check if a computed digest matches the expected SHA-256 and size.
     */
    private boolean digestMatches(ReplicaObjectStore.DigestResult digest, String expectedSha256, long expectedSize) {
        return digest.sha256Hex().equalsIgnoreCase(expectedSha256) && digest.size() == expectedSize;
    }

    /**
     * Helper: retry a job with exponential backoff and jitter.
     * Also writes an alert if we've exceeded alertAfterAttempts.
     *
     * @return RETRY outcome
     */
    private Outcome retry(FileTransferJobStore.Job job, Duration delay, String errorCode, String message) {
        if (!jobs.retry(job, delay, errorCode, message)) {
            log.warn("Lost lease while retrying job {}", job.jobId());
            return Outcome.LOST_LEASE;
        }

        if (job.attempts() == props.getAlertAfterAttempts()) {
            log.warn("Job {} reached alert threshold at attempt {}", job.jobId(), job.attempts());
            jobs.alert(job, "RETRY_EXHAUSTED", "Job exhausted retry limit at " + job.attempts() + " attempts");
        }

        return Outcome.RETRY;
    }

    /**
     * Complete the job. When completion is refused because the target replica is DELETING/DELETED (the GC removed it),
     * the job is quarantined with TARGET_DELETED instead of being recorded as done; otherwise a refusal means a lost lease.
     */
    private Outcome completeOrExplain(FileTransferJobStore.Job job, ReplicaObjectStore.DigestResult verified) {
        // The lost-response/target-already-present path must pass the same final gate as a fresh upload.
        // Otherwise a readback racing withdrawal could publish AVAILABLE after consent was revoked.
        if (!consentGate.replicationAllowed(job.ownerGid())
                || (job.consentEpoch()!=null&&!job.consentEpoch().equals(consentGate.currentEpoch(job.ownerGid())))) {
            return jobs.pauseConsent(job)?Outcome.PAUSED:Outcome.LOST_LEASE;
        }
        if (jobs.complete(job, verified)) {
            log.info("Job {} completed successfully", job.jobId());
            return Outcome.COMPLETED;
        }
        if (jobs.isTargetReplicaDeleted(job)) {
            log.warn("Target replica of job {} was physically deleted; quarantining", job.jobId());
            return quarantine(job, "TARGET_DELETED", "Target replica was deleted by the file GC");
        }
        log.warn("Lost lease on job {} during completion", job.jobId());
        return Outcome.LOST_LEASE;
    }

    /**
     * Helper: quarantine a job with an error code and message.
     *
     * @return QUARANTINED outcome
     */
    private Outcome quarantine(FileTransferJobStore.Job job, String code, String message) {
        if (!jobs.quarantine(job, code, message)) {
            log.warn("Lost lease while quarantining job {}", job.jobId());
            return Outcome.LOST_LEASE;
        }
        return Outcome.QUARANTINED;
    }

    /**
     * Create a secure temp file in the configured temp directory.
     * Attempts to set restrictive permissions on POSIX systems.
     */
    private Path createSecureTempFile() throws IOException {
        Path tempDir = tempDirPath();
        Path tmpFile = Files.createTempFile(tempDir, "frw-", ".tmp");
        try {
            // Try to set restrictive permissions on POSIX systems
            var perms = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(tmpFile, perms);
        } catch (UnsupportedOperationException e) {
            // Not a POSIX system; skip permission setting
            log.debug("POSIX permissions not supported on this system");
        }
        return tmpFile;
    }

    /** Configured temp directory; a blank value (for example an unset TEMP_DIR placeholder) means the JVM default. */
    private Path tempDirPath() {
        String dir = props.getTempDir();
        return java.nio.file.Paths.get(dir == null || dir.isBlank() ? System.getProperty("java.io.tmpdir") : dir);
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }

    /**
     * Bridges a multipart upload to the job row: persists the upload id with a fenced update, extends the lease every
     * {@link #HEARTBEAT_EVERY_PARTS} parts or when the heartbeat interval has elapsed (whichever comes first, so a
     * 10000-part upload makes at most a few thousand cheap updates), and refuses to complete once the lease is gone.
     */
    private final class MultipartTracker implements UploadListener {
        private final FileTransferJobStore.Job job;
        private final long heartbeatNanos;
        private boolean started;
        private boolean lost;
        private int partsSinceExtend;
        private long lastExtendNanos = System.nanoTime();

        MultipartTracker(FileTransferJobStore.Job job) {
            this.job = job;
            long thirdOfLease = Duration.ofSeconds(props.getLeaseSeconds()).toNanos() / 3;
            this.heartbeatNanos = Math.min(HEARTBEAT_MAX_NANOS, Math.max(HEARTBEAT_MIN_NANOS, thirdOfLease));
        }

        @Override
        public void onMultipartStarted(String uploadId) {
            started = true;
            if (!jobs.setMultipartUpload(job, uploadId)) {
                lost = true;
                throw new LeaseLostException("Lease lost before the multipart upload id could be recorded for job " + job.jobId());
            }
            lastExtendNanos = System.nanoTime();
        }

        @Override
        public void onPartUploaded(int partNumber, int totalParts) {
            partsSinceExtend++;
            if (partsSinceExtend >= HEARTBEAT_EVERY_PARTS || System.nanoTime() - lastExtendNanos >= heartbeatNanos) {
                extend("part " + partNumber + "/" + totalParts);
            }
        }

        @Override
        public void onBeforeComplete() {
            extend("completion");
        }

        private void extend(String where) {
            if (!jobs.extendLease(job, Duration.ofSeconds(props.getLeaseSeconds()))) {
                lost = true;
                throw new LeaseLostException("Lease lost at " + where + " of the multipart upload for job " + job.jobId());
            }
            partsSinceExtend = 0;
            lastExtendNanos = System.nanoTime();
        }

        /** Forget the upload id once the upload completed or was aborted, unless another holder owns the job now. */
        void finish() {
            if (!started || lost) {
                return;
            }
            try {
                jobs.clearMultipartUpload(job);
            } catch (RuntimeException failure) {
                log.warn("Could not clear the multipart upload id of job {}", job.jobId(), failure);
            }
        }
    }

    /**
     * Truncate a string to max length (for error messages).
     */
    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }
}
