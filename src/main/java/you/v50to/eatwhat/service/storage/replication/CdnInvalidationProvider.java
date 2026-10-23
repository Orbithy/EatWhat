package you.v50to.eatwhat.service.storage.replication;

import java.util.List;
import java.util.UUID;

/**
 * Vendor-neutral CDN invalidation port. Implementations receive CDN-relative paths only (no scheme, host, query or
 * signature) and report one of three outcomes. No vendor-specific code exists in this project yet.
 */
public interface CdnInvalidationProvider {
    enum Reason { REVOKE, DELETE }

    sealed interface Result permits Accepted, Retryable, Permanent { }

    /** The provider took the request; {@code requestId} is its own identifier when it returned one (may be null). */
    record Accepted(String requestId) implements Result { }

    /** Transient failure (429, 5xx, I/O, timeout): try again later. {@code code} is a short non-secret label. */
    record Retryable(String code) implements Result { }

    /** The request can never succeed as sent (for example a 4xx): stop and alert. */
    record Permanent(String code) implements Result { }

    /**
     * Submit one invalidation.
     *
     * @param cdnPaths CDN-relative paths, each starting with "/"
     * @param reason why the paths are invalidated
     * @param taskId idempotency identifier of the queue task
     */
    Result submit(List<String> cdnPaths, Reason reason, UUID taskId);
}
