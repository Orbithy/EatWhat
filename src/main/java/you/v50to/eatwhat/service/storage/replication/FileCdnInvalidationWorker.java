package you.v50to.eatwhat.service.storage.replication;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import you.v50to.eatwhat.config.FileCdnInvalidationProperties;
import you.v50to.eatwhat.config.S3StorageProperties;
import you.v50to.eatwhat.service.storage.CdnPaths;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Processes claimed CDN invalidation tasks. The CDN-relative path is derived here, at submit time, from the revision's
 * final key and the public base URL (never stored, never a presigned URL).
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.storage.files.cdn-invalidation.enabled", havingValue = "true")
public class FileCdnInvalidationWorker {
    public enum Outcome { DONE, SKIPPED, RETRY, QUARANTINED, LOST_LEASE }

    private final FileCdnInvalidationStore store;
    private final CdnInvalidationProvider provider;
    private final FileCdnInvalidationProperties props;
    private final Supplier<String> publicBaseUrl;
    private final Random rng;

    @Autowired
    public FileCdnInvalidationWorker(FileCdnInvalidationStore store, CdnInvalidationProvider provider,
                                     FileCdnInvalidationProperties props, S3StorageProperties storage) {
        this(store, provider, props, storage::getPublicBaseUrl, new Random());
    }

    public FileCdnInvalidationWorker(FileCdnInvalidationStore store, CdnInvalidationProvider provider,
                                     FileCdnInvalidationProperties props, Supplier<String> publicBaseUrl, Random rng) {
        this.store = Objects.requireNonNull(store, "store");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.props = Objects.requireNonNull(props, "props");
        this.publicBaseUrl = Objects.requireNonNull(publicBaseUrl, "publicBaseUrl");
        this.rng = Objects.requireNonNull(rng, "rng");
    }

    /** Claim a batch and process it. Returns the number of tasks processed. */
    public int runOnce() {
        List<FileCdnInvalidationStore.Task> tasks = store.claim(props.getBatch(), props.getLease());
        int processed = 0;
        for (FileCdnInvalidationStore.Task task : tasks) {
            if (Thread.currentThread().isInterrupted()) {
                break; // unprocessed leases simply expire and are reclaimed
            }
            try {
                process(task);
            } catch (RuntimeException failure) {
                log.error("CDN invalidation task {} failed unexpectedly; the lease will expire and be retried", task.taskId(), failure);
            }
            processed++;
        }
        return processed;
    }

    public Outcome process(FileCdnInvalidationStore.Task task) {
        // The revision was referenced publicly again before we submitted: the revoke is moot.
        // Decision and write are one atomic statement (a stale lease simply falls through and fails its next fenced write).
        if ("REVOKE".equals(task.reason()) && store.skipIfRevived(task)) {
            return Outcome.SKIPPED;
        }
        if (provider instanceof NoopCdnProvider) {
            return skipped(task, "NO_PROVIDER");
        }
        String base = publicBaseUrl.get();
        if (!StringUtils.hasText(base)) {
            return skipped(task, "NO_PUBLIC_BASE_URL");
        }
        String path = CdnPaths.relativePath(base, task.finalKey());
        if (path == null) {
            return quarantine(task, "INVALID_PATH");
        }
        CdnInvalidationProvider.Result result;
        try {
            result = provider.submit(List.of(path), CdnInvalidationProvider.Reason.valueOf(task.reason()), task.taskId());
        } catch (RuntimeException failure) {
            // Never put exception text (could carry URLs or secrets) into the database.
            result = new CdnInvalidationProvider.Retryable("PROVIDER_ERROR");
        }
        if (result instanceof CdnInvalidationProvider.Accepted accepted) {
            return store.markDone(task, accepted.requestId()) ? Outcome.DONE : Outcome.LOST_LEASE;
        }
        if (result instanceof CdnInvalidationProvider.Permanent permanent) {
            return quarantine(task, permanent.code());
        }
        CdnInvalidationProvider.Retryable retryable = (CdnInvalidationProvider.Retryable) result;
        if (task.attempts() >= props.getAlertAfterAttempts()) { // the store dedupes: one alert per task
            store.alertRetryExhausted(task);
        }
        Duration delay = FileTransferJobStore.backoff(Math.max(1, task.attempts()), props.getRetryBase(),
                props.getRetryMax(), props.getJitter(), rng);
        return store.retry(task, delay, retryable.code()) ? Outcome.RETRY : Outcome.LOST_LEASE;
    }

    private Outcome skipped(FileCdnInvalidationStore.Task task, String reason) {
        return store.markSkipped(task, reason) ? Outcome.SKIPPED : Outcome.LOST_LEASE;
    }

    private Outcome quarantine(FileCdnInvalidationStore.Task task, String code) {
        return store.quarantine(task, code) ? Outcome.QUARANTINED : Outcome.LOST_LEASE;
    }
}
