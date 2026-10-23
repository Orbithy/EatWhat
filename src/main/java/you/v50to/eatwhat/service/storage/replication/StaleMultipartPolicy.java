package you.v50to.eatwhat.service.storage.replication;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.config.FileGcProperties;

import java.time.Duration;

/**
 * The single definition of "a stale multipart upload", shared by the report-only inventory and the GC so they can
 * never disagree. An in-progress multipart upload is stale when it is older than
 * {@code max(multipart-ttl, safety-window)}, lies under a managed prefix, and nobody can own it: no READY/LEASED
 * transfer job records its upload id and no open upload intent has its key.
 *
 * <p>This class only decides; it never calls the object store.
 */
final class StaleMultipartPolicy {
    private StaleMultipartPolicy() { }

    /** Minimum age of an upload before it can be reported or aborted. */
    static Duration minimumAge(FileGcProperties props) {
        Duration ttl = props.getMultipartTtl();
        Duration window = props.getSafetyWindow();
        return ttl.compareTo(window) >= 0 ? ttl : window;
    }

    /**
     * Only keys of this application's managed prefixes (verified and staging) are ever considered, never legacy
     * prefixes or anything else in the bucket, and never an odd-looking key.
     */
    static boolean isManagedKey(FileGcProperties props, String key) {
        if (key == null || key.isBlank() || key.startsWith("/") || key.endsWith("/")
                || key.contains("..") || key.contains("\u0000") || key.contains("\\")) {
            return false;
        }
        for (String legacy : props.getLegacyPrefixes()) {
            if (legacy != null && !legacy.isBlank() && key.startsWith(legacy)) {
                return false;
            }
        }
        return hasPrefix(key, props.getVerifiedPrefix()) || hasPrefix(key, props.getStagingPrefix());
    }

    private static boolean hasPrefix(String key, String prefix) {
        return prefix != null && !prefix.isBlank() && key.length() > prefix.length() && key.startsWith(prefix);
    }

    /**
     * True when something may still need the upload: a READY/LEASED transfer job holds its upload id, or an open upload
     * intent (not completed/rejected and not expired, database clock) has this key as its staging key.
     */
    static boolean isOwned(JdbcTemplate jdbc, String key, String uploadId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM eatwhat_file.file_transfer_job
                               WHERE multipart_upload_id=? AND status IN ('READY','LEASED'))
                    OR EXISTS (SELECT 1 FROM eatwhat_file.upload_intent
                               WHERE staging_key=? AND status IN ('PENDING','CLAIMED')
                                 AND expires_at > clock_timestamp())
                """, Boolean.class, uploadId, key));
    }
}
