package you.v50to.eatwhat.service.storage.replication;

import java.util.List;
import java.util.UUID;

/**
 * Default provider: no CDN integration. The worker recognises it and marks tasks SKIPPED with skip_reason NO_PROVIDER
 * (it never submits anything), so a deployment without a CDN is not blocked forever behind READY tasks once the
 * worker is enabled.
 */
public final class NoopCdnProvider implements CdnInvalidationProvider {
    @Override
    public Result submit(List<String> cdnPaths, Reason reason, UUID taskId) {
        // Not reached by the worker (it skips before submitting); a defensive permanent outcome if called directly.
        return new Permanent("NO_PROVIDER");
    }
}
